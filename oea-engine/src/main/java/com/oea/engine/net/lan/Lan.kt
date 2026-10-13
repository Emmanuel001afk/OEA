package com.oea.engine.net.lan

import android.util.Log
import com.oea.engine.net.Channel
import com.oea.engine.net.Link
import com.oea.engine.net.LinkListener
import com.oea.engine.net.TransportKind
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** A local IPv4 address usable for LAN play, with its broadcast address. */
data class LocalAddress(val interfaceName: String, val address: Inet4Address, val broadcast: InetAddress?, val isHotspot: Boolean) {
    val label: String get() = when {
        isHotspot -> "Hotspot"
        interfaceName.startsWith("wlan") -> "Wi-Fi"
        interfaceName.startsWith("eth") -> "Ethernet"
        interfaceName.startsWith("rndis") || interfaceName.startsWith("usb") -> "USB tethering"
        else -> interfaceName
    }
}

/** Network interface helpers for Wi-Fi and hotspot play. */
object NetInterfaces {
    /** Private IPv4 addresses of interfaces that are up (Wi-Fi, hotspot, Ethernet, USB tethering). Mobile data is excluded. */
    fun localAddresses(): List<LocalAddress> {
        val out = ArrayList<LocalAddress>()
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
        for (ni in ifaces) {
            val up = runCatching { ni.isUp && !ni.isLoopback }.getOrDefault(false)
            if (!up) continue
            val name = ni.name.orEmpty()
            if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp") || name.startsWith("dummy") || name.startsWith("tun")) continue
            for (ia in ni.interfaceAddresses.orEmpty()) {
                val a = ia.address as? Inet4Address ?: continue
                if (!a.isSiteLocalAddress && !a.isLinkLocalAddress) continue
                val hotspot = name.startsWith("ap") || name.startsWith("swlan") || name.contains("softap") ||
                    (name.startsWith("wlan") && a.hostAddress?.endsWith(".1") == true && a.hostAddress?.startsWith("192.168.43.") == true)
                out += LocalAddress(name, a, ia.broadcast, hotspot)
            }
        }
        // Wi-Fi and hotspot first.
        return out.sortedBy { if (it.isHotspot) 0 else if (it.interfaceName.startsWith("wlan")) 1 else 2 }
    }

    /** Broadcast targets: every interface's subnet broadcast plus the global one. */
    fun broadcastTargets(): List<InetAddress> {
        val list = localAddresses().mapNotNull { it.broadcast }.toMutableList()
        runCatching { list += InetAddress.getByName("255.255.255.255") }
        return list.distinct()
    }
}

/** Transport framing constants. */
internal object LanFrames {
    const val DATA = 0
    const val TOKEN = 1
    const val FALLBACK_UNRELIABLE = 2
    const val UDP_MAGIC_0 = 0x0E
    const val UDP_MAGIC_1 = 0xA1.toByte().toInt()
    const val UDP_HELLO = 1
    const val UDP_ACK = 2
    const val UDP_DATA = 3
    const val MAX_FRAME = 65535
}

/**
 * Host side of LAN play: a TCP listener (reliable, ordered) plus a UDP socket
 * on the same port (unreliable), so position updates never queue behind a
 * lost TCP packet.
 */
class LanServer(private val listener: LinkListener) {
    private var server: ServerSocket? = null
    private var udp: DatagramSocket? = null
    private val byToken = ConcurrentHashMap<Int, ServerLink>()
    @Volatile private var running = false
    var port = 0
        private set

    /** Starts listening; tries [preferredPort] first, then any free port. Returns the port. */
    fun start(preferredPort: Int = DEFAULT_PORT): Int {
        val ss = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(preferredPort)) } }.getOrNull()
            ?: ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(0)) }
        server = ss
        port = ss.localPort
        udp = runCatching { DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(port)) } }.getOrNull()
        running = true
        Thread({ acceptLoop(ss) }, "oea-lan-accept").apply { isDaemon = true; start() }
        udp?.let { u -> Thread({ udpLoop(u) }, "oea-lan-udp").apply { isDaemon = true; start() } }
        return port
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val s = try { ss.accept() } catch (e: IOException) { if (running) Log.w(TAG, "accept failed", e); break }
            try {
                s.tcpNoDelay = true
                s.keepAlive = true
                var token: Int
                do { token = Random.nextInt() } while (token == 0 || byToken.containsKey(token))
                val link = ServerLink(s, token)
                byToken[token] = link
                link.start()
            } catch (e: IOException) {
                runCatching { s.close() }
            }
        }
    }

    private fun udpLoop(u: DatagramSocket) {
        val buf = ByteArray(LanFrames.MAX_FRAME)
        val packet = DatagramPacket(buf, buf.size)
        while (running) {
            try {
                packet.setData(buf, 0, buf.size)
                u.receive(packet)
                if (packet.length < 7 || buf[0].toInt() != LanFrames.UDP_MAGIC_0 || buf[1].toInt() != LanFrames.UDP_MAGIC_1) continue
                val token = ((buf[2].toInt() and 0xFF) shl 24) or ((buf[3].toInt() and 0xFF) shl 16) or ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
                val link = byToken[token] ?: continue
                val type = buf[6].toInt()
                link.udpAddress = packet.socketAddress
                link.lastHeardMs = System.currentTimeMillis()
                when (type) {
                    LanFrames.UDP_HELLO -> link.sendUdp(LanFrames.UDP_ACK, ByteArray(0), 0, 0)
                    LanFrames.UDP_DATA -> if (link.isUp) listener.onLinkMessage(link, Channel.UNRELIABLE, buf.copyOfRange(7, packet.length))
                }
            } catch (e: IOException) {
                if (running) Log.w(TAG, "udp receive failed", e)
                if (u.isClosed) break
            }
        }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        runCatching { udp?.close() }
        byToken.values.forEach { it.close("Host stopped") }
        byToken.clear()
    }

    private inner class ServerLink(private val socket: Socket, private val token: Int) : Link(TransportKind.LAN) {
        @Volatile var udpAddress: SocketAddress? = null
        private val out = LinkedBlockingQueue<ByteArray>()
        @Volatile private var closed = false
        private val remote = socket.inetAddress?.hostAddress.orEmpty()
        override val description get() = "Wi-Fi • $remote${if (udpAddress != null) "" else " (TCP)"}"

        fun start() {
            Thread({ writeLoop() }, "oea-lan-w$id").apply { isDaemon = true; start() }
            // First frame tells the client its UDP token and the UDP port.
            val hello = ByteArray(7)
            hello[0] = LanFrames.TOKEN.toByte()
            hello[1] = (token shr 24).toByte(); hello[2] = (token shr 16).toByte(); hello[3] = (token shr 8).toByte(); hello[4] = token.toByte()
            hello[5] = (port shr 8).toByte(); hello[6] = port.toByte()
            out.offer(hello)
            isUp = true
            listener.onLinkUp(this)
            Thread({ readLoop() }, "oea-lan-r$id").apply { isDaemon = true; start() }
        }

        private fun readLoop() {
            try {
                val input = DataInputStream(socket.getInputStream().buffered())
                while (!closed) {
                    val len = input.readUnsignedShort()
                    if (len < 1) continue
                    val frame = ByteArray(len)
                    input.readFully(frame)
                    lastHeardMs = System.currentTimeMillis()
                    when (frame[0].toInt()) {
                        LanFrames.DATA -> listener.onLinkMessage(this, Channel.RELIABLE, frame.copyOfRange(1, len))
                        LanFrames.FALLBACK_UNRELIABLE -> listener.onLinkMessage(this, Channel.UNRELIABLE, frame.copyOfRange(1, len))
                    }
                }
            } catch (e: IOException) {
                close("Connection lost")
            }
        }

        private fun writeLoop() {
            try {
                val output = DataOutputStream(socket.getOutputStream().buffered())
                while (!closed) {
                    val frame = out.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    output.writeShort(frame.size)
                    output.write(frame)
                    // Coalesce whatever else is queued, then flush once.
                    while (true) {
                        val next = out.poll() ?: break
                        output.writeShort(next.size); output.write(next)
                    }
                    output.flush()
                }
            } catch (e: IOException) {
                close("Connection lost")
            } catch (e: InterruptedException) {
                close("Stopped")
            }
        }

        fun sendUdp(type: Int, data: ByteArray, offset: Int, length: Int): Boolean {
            val addr = udpAddress ?: return false
            val u = udp ?: return false
            val pkt = ByteArray(7 + length)
            pkt[0] = LanFrames.UDP_MAGIC_0.toByte(); pkt[1] = LanFrames.UDP_MAGIC_1.toByte()
            pkt[2] = (token shr 24).toByte(); pkt[3] = (token shr 16).toByte(); pkt[4] = (token shr 8).toByte(); pkt[5] = token.toByte()
            pkt[6] = type.toByte()
            System.arraycopy(data, offset, pkt, 7, length)
            return runCatching { u.send(DatagramPacket(pkt, pkt.size, addr)); true }.getOrDefault(false)
        }

        override fun send(channel: Channel, data: ByteArray, offset: Int, length: Int): Boolean {
            if (closed || length + 1 > LanFrames.MAX_FRAME) return false
            if (channel == Channel.UNRELIABLE && sendUdp(LanFrames.UDP_DATA, data, offset, length)) return true
            val frame = ByteArray(length + 1)
            frame[0] = (if (channel == Channel.RELIABLE) LanFrames.DATA else LanFrames.FALLBACK_UNRELIABLE).toByte()
            System.arraycopy(data, offset, frame, 1, length)
            return out.offer(frame)
        }

        override fun close(reason: String) {
            if (closed) return
            closed = true
            isUp = false
            byToken.remove(token)
            runCatching { socket.close() }
            listener.onLinkDown(this, reason)
        }
    }

    companion object {
        const val DEFAULT_PORT = 47812
        private const val TAG = "OeaLanServer"
    }
}

/** Client side of LAN play. [connect] blocks, so call it off the main thread. */
class LanClientLink private constructor(private val socket: Socket, private val listener: LinkListener, private val host: InetAddress) : Link(TransportKind.LAN) {
    private val out = LinkedBlockingQueue<ByteArray>()
    @Volatile private var closed = false
    @Volatile private var token = 0
    @Volatile private var udpPort = 0
    @Volatile private var udpWorks = false
    private var udp: DatagramSocket? = null
    override val description get() = "Wi-Fi • ${host.hostAddress}${if (udpWorks) "" else " (TCP)"}"

    private fun start() {
        Thread({ writeLoop() }, "oea-lanc-w").apply { isDaemon = true; start() }
        Thread({ readLoop() }, "oea-lanc-r").apply { isDaemon = true; start() }
    }

    private fun readLoop() {
        try {
            val input = DataInputStream(socket.getInputStream().buffered())
            while (!closed) {
                val len = input.readUnsignedShort()
                if (len < 1) continue
                val frame = ByteArray(len)
                input.readFully(frame)
                lastHeardMs = System.currentTimeMillis()
                when (frame[0].toInt()) {
                    LanFrames.TOKEN -> if (len >= 7) {
                        token = ((frame[1].toInt() and 0xFF) shl 24) or ((frame[2].toInt() and 0xFF) shl 16) or ((frame[3].toInt() and 0xFF) shl 8) or (frame[4].toInt() and 0xFF)
                        udpPort = ((frame[5].toInt() and 0xFF) shl 8) or (frame[6].toInt() and 0xFF)
                        startUdp()
                        isUp = true
                        listener.onLinkUp(this)
                    }
                    LanFrames.DATA -> listener.onLinkMessage(this, Channel.RELIABLE, frame.copyOfRange(1, len))
                    LanFrames.FALLBACK_UNRELIABLE -> listener.onLinkMessage(this, Channel.UNRELIABLE, frame.copyOfRange(1, len))
                }
            }
        } catch (e: IOException) {
            close("Lost connection to the host")
        }
    }

    private fun writeLoop() {
        try {
            val output = DataOutputStream(socket.getOutputStream().buffered())
            while (!closed) {
                val frame = out.poll(500, TimeUnit.MILLISECONDS) ?: continue
                output.writeShort(frame.size); output.write(frame)
                while (true) { val next = out.poll() ?: break; output.writeShort(next.size); output.write(next) }
                output.flush()
            }
        } catch (e: IOException) {
            close("Lost connection to the host")
        } catch (e: InterruptedException) {
            close("Stopped")
        }
    }

    private fun startUdp() {
        val u = runCatching { DatagramSocket() }.getOrNull() ?: return
        udp = u
        Thread({
            val buf = ByteArray(LanFrames.MAX_FRAME)
            val packet = DatagramPacket(buf, buf.size)
            // Say hello a few times; UDP is used once the host acknowledges.
            Thread({
                repeat(6) {
                    if (closed || udpWorks) return@Thread
                    sendUdp(LanFrames.UDP_HELLO, ByteArray(0), 0, 0)
                    try { Thread.sleep(250) } catch (_: InterruptedException) { return@Thread }
                }
            }, "oea-lanc-hello").apply { isDaemon = true; start() }
            while (!closed) {
                try {
                    packet.setData(buf, 0, buf.size)
                    u.receive(packet)
                    if (packet.length < 7 || buf[0].toInt() != LanFrames.UDP_MAGIC_0 || buf[1].toInt() != LanFrames.UDP_MAGIC_1) continue
                    lastHeardMs = System.currentTimeMillis()
                    when (buf[6].toInt()) {
                        LanFrames.UDP_ACK -> udpWorks = true
                        LanFrames.UDP_DATA -> { udpWorks = true; listener.onLinkMessage(this, Channel.UNRELIABLE, buf.copyOfRange(7, packet.length)) }
                    }
                } catch (e: IOException) {
                    if (u.isClosed) break
                }
            }
        }, "oea-lanc-udp").apply { isDaemon = true; start() }
    }

    private fun sendUdp(type: Int, data: ByteArray, offset: Int, length: Int): Boolean {
        val u = udp ?: return false
        if (token == 0 || udpPort == 0) return false
        val pkt = ByteArray(7 + length)
        pkt[0] = LanFrames.UDP_MAGIC_0.toByte(); pkt[1] = LanFrames.UDP_MAGIC_1.toByte()
        pkt[2] = (token shr 24).toByte(); pkt[3] = (token shr 16).toByte(); pkt[4] = (token shr 8).toByte(); pkt[5] = token.toByte()
        pkt[6] = type.toByte()
        System.arraycopy(data, offset, pkt, 7, length)
        return runCatching { u.send(DatagramPacket(pkt, pkt.size, host, udpPort)); true }.getOrDefault(false)
    }

    override fun send(channel: Channel, data: ByteArray, offset: Int, length: Int): Boolean {
        if (closed || length + 1 > LanFrames.MAX_FRAME) return false
        if (channel == Channel.UNRELIABLE && udpWorks && sendUdp(LanFrames.UDP_DATA, data, offset, length)) return true
        val frame = ByteArray(length + 1)
        frame[0] = (if (channel == Channel.RELIABLE) LanFrames.DATA else LanFrames.FALLBACK_UNRELIABLE).toByte()
        System.arraycopy(data, offset, frame, 1, length)
        return out.offer(frame)
    }

    override fun close(reason: String) {
        if (closed) return
        closed = true
        isUp = false
        runCatching { socket.close() }
        runCatching { udp?.close() }
        listener.onLinkDown(this, reason)
    }

    companion object {
        /** Connects to a host; throws [IOException] with a readable message on failure. Blocking. */
        fun connect(address: String, port: Int, listener: LinkListener, timeoutMs: Int = 6000): LanClientLink {
            val host = InetAddress.getByName(address)
            val s = Socket()
            try {
                s.tcpNoDelay = true
                s.keepAlive = true
                s.connect(InetSocketAddress(host, port), timeoutMs)
            } catch (e: IOException) {
                runCatching { s.close() }
                throw IOException(when (e) {
                    is java.net.SocketTimeoutException -> "The host didn't answer. Make sure both phones are on the same Wi-Fi or hotspot."
                    is java.net.ConnectException -> "No game is running at $address:$port anymore."
                    is java.net.NoRouteToHostException -> "Can't reach $address. Is this phone on the same network?"
                    else -> e.message ?: "Connection failed"
                }, e)
            }
            return LanClientLink(s, listener, host).also { it.start() }
        }
    }
}
