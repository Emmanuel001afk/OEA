package com.oea.engine.net.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.oea.engine.OeaEngine
import com.oea.engine.core.OeaGuard
import com.oea.engine.net.ByteReader
import com.oea.engine.net.ByteWriter
import com.oea.engine.net.Codes
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/** A game found on the local network. */
data class LanGame(
    val sessionId: Long,
    val gameId: String,
    val roomName: String,
    val hostName: String,
    val address: String,
    val port: Int,
    val players: Int,
    val maxPlayers: Int,
    val inLobby: Boolean,
    val protocol: Int,
    val lastSeen: Long,
) {
    val isFull get() = players >= maxPlayers
    val compatible get() = protocol == OeaEngine.PROTOCOL
}

/** What a host advertises. Mutable fields are re-read for every beacon. */
class LanBeacon(
    val sessionId: Long,
    val gameId: String,
    @Volatile var roomName: String,
    @Volatile var hostName: String,
    @Volatile var port: Int,
    @Volatile var players: Int,
    @Volatile var maxPlayers: Int,
    @Volatile var inLobby: Boolean = true,
)

internal object DiscoveryPacket {
    private val MAGIC = byteArrayOf('O'.code.toByte(), 'E'.code.toByte(), 'A'.code.toByte(), 'D'.code.toByte())
    const val VERSION = 1
    const val BEACON = 1
    const val QUERY = 2

    fun beacon(b: LanBeacon): ByteArray = ByteWriter(96).bytes(MAGIC).u8(VERSION).u8(BEACON).u16(OeaEngine.PROTOCOL).i64(b.sessionId)
        .u16(b.port).u8(b.players).u8(b.maxPlayers).bool(b.inLobby).string(b.gameId).string(b.roomName).string(b.hostName).toByteArray()

    fun query(): ByteArray = ByteWriter(8).bytes(MAGIC).u8(VERSION).u8(QUERY).toByteArray()

    /** Returns the packet type, or -1 if it isn't ours. */
    fun type(data: ByteArray, len: Int): Int {
        if (len < 6) return -1
        for (i in 0 until 4) if (data[i] != MAGIC[i]) return -1
        if (data[4].toInt() != VERSION) return -1
        return data[5].toInt()
    }

    fun parseBeacon(data: ByteArray, len: Int, from: InetAddress): LanGame? = runCatching {
        val r = ByteReader(data, 6, len)
        val protocol = r.u16()
        val sessionId = r.i64()
        val port = r.u16()
        val players = r.u8()
        val max = r.u8()
        val lobby = r.bool()
        LanGame(sessionId, r.string(64), r.string(96), r.string(64), from.hostAddress.orEmpty(), port, players, max, lobby, protocol, System.currentTimeMillis())
    }.getOrNull()
}

/**
 * Host-side discovery: UDP beacons on every interface (Wi-Fi and hotspot),
 * answers to client queries, and an mDNS/NSD service as a second path for
 * routers that filter broadcast.
 */
class LanAdvertiser(private val context: Context, private val beacon: LanBeacon) {
    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    private var nsdListener: NsdManager.RegistrationListener? = null

    fun start() {
        if (running) return
        running = true
        lock = acquireLock(context)
        val s = runCatching { DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(DISCOVERY_PORT)) } }.getOrNull()
            ?: runCatching { DatagramSocket().apply { broadcast = true } }.getOrNull()
        socket = s
        if (s != null) {
            OeaGuard.start("oea-lan-beacon", "lan-discovery") { sendLoop(s) }
            OeaGuard.start("oea-lan-query", "lan-discovery") { queryLoop(s) }
        }
        registerNsd()
    }

    private fun sendLoop(s: DatagramSocket) {
        while (running) {
            val data = DiscoveryPacket.beacon(beacon)
            for (target in NetInterfaces.broadcastTargets()) {
                runCatching { s.send(DatagramPacket(data, data.size, target, DISCOVERY_PORT)) }
            }
            try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
        }
    }

    private fun queryLoop(s: DatagramSocket) {
        val buf = ByteArray(512)
        val p = DatagramPacket(buf, buf.size)
        while (running) {
            try {
                p.setData(buf, 0, buf.size)
                s.receive(p)
                if (DiscoveryPacket.type(buf, p.length) == DiscoveryPacket.QUERY) {
                    val data = DiscoveryPacket.beacon(beacon)
                    runCatching { s.send(DatagramPacket(data, data.size, p.address, p.port)) }
                }
            } catch (e: IOException) {
                if (s.isClosed) break
            }
        }
    }

    private fun registerNsd() {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return
        val info = NsdServiceInfo().apply {
            serviceName = "OEA ${beacon.roomName}".take(60)
            serviceType = NSD_TYPE
            port = beacon.port
            runCatching {
                setAttribute("s", beacon.sessionId.toString())
                setAttribute("g", beacon.gameId)
                setAttribute("v", OeaEngine.PROTOCOL.toString())
                setAttribute("h", beacon.hostName.take(40))
            }
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { Log.w(TAG, "NSD registration failed: $errorCode") }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l); nsdListener = l }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        nsdListener?.let { l -> runCatching { context.getSystemService(NsdManager::class.java)?.unregisterService(l) } }
        nsdListener = null
        runCatching { lock?.release() }
        lock = null
    }

    companion object {
        const val DISCOVERY_PORT = 47811
        const val NSD_TYPE = "_oeagame._tcp."
        private const val TAG = "OeaLanAdvertiser"

        internal fun acquireLock(context: Context): WifiManager.MulticastLock? = runCatching {
            context.applicationContext.getSystemService(WifiManager::class.java)?.createMulticastLock("oea-games")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }
}

/**
 * Client-side discovery: listens for beacons, broadcasts queries and browses
 * NSD. [onChanged] runs on the main thread with the current list.
 */
class LanBrowser(private val context: Context, private val gameId: String?, private val onChanged: (List<LanGame>) -> Unit) {
    private val games = ConcurrentHashMap<Long, LanGame>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    private var nsdListener: NsdManager.DiscoveryListener? = null
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    val current: List<LanGame> get() = games.values.sortedBy { it.roomName.lowercase() }

    fun start() {
        if (running) return
        running = true
        lock = LanAdvertiser.acquireLock(context)
        val s = runCatching { DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(LanAdvertiser.DISCOVERY_PORT)) } }.getOrNull()
            ?: runCatching { DatagramSocket().apply { broadcast = true } }.getOrNull()
        socket = s
        if (s != null) {
            OeaGuard.start("oea-lan-browse", "lan-discovery") { receiveLoop(s) }
            OeaGuard.start("oea-lan-ask", "lan-discovery") { queryLoop(s) }
        }
        startNsd()
        main.postDelayed(expiry, 1000)
    }

    private val expiry = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.currentTimeMillis()
            if (games.values.removeIf { now - it.lastSeen > 4500 }) publish()
            main.postDelayed(this, 1000)
        }
    }

    private fun receiveLoop(s: DatagramSocket) {
        val buf = ByteArray(1024)
        val p = DatagramPacket(buf, buf.size)
        while (running) {
            try {
                p.setData(buf, 0, buf.size)
                s.receive(p)
                if (DiscoveryPacket.type(buf, p.length) != DiscoveryPacket.BEACON) continue
                val g = DiscoveryPacket.parseBeacon(buf, p.length, p.address) ?: continue
                if (gameId != null && g.gameId != gameId) continue
                val isNew = games.put(g.sessionId, g)?.let { it.players != g.players || it.inLobby != g.inLobby || it.roomName != g.roomName } ?: true
                if (isNew) publish()
            } catch (e: IOException) {
                if (s.isClosed) break
            }
        }
    }

    private fun queryLoop(s: DatagramSocket) {
        while (running) {
            val q = DiscoveryPacket.query()
            for (t in NetInterfaces.broadcastTargets()) runCatching { s.send(DatagramPacket(q, q.size, t, LanAdvertiser.DISCOVERY_PORT)) }
            try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
        }
    }

    private fun publish() {
        val list = current
        main.post { if (running) onChanged(list) }
    }

    private fun startNsd() {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) = OeaGuard.run("lan-discovery") {
                OeaGuard.post("lan-discovery", main) { resolveQueue.addLast(serviceInfo); resolveNext() }
            }
        }
        runCatching { nsd.discoverServices(LanAdvertiser.NSD_TYPE, NsdManager.PROTOCOL_DNS_SD, l); nsdListener = l }
    }

    /** NSD resolves one service at a time on older Android versions, so queue them. */
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving || !running) return
        val info = resolveQueue.removeFirstOrNull() ?: return
        val nsd = context.getSystemService(NsdManager::class.java) ?: return
        resolving = true
        runCatching {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = OeaGuard.run("lan-discovery") {
                    OeaGuard.post("lan-discovery", main) { resolving = false; resolveNext() }
                }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) = OeaGuard.run("lan-discovery") {
                    val host = serviceInfo.host
                    val attrs = runCatching { serviceInfo.attributes }.getOrNull().orEmpty()
                    val sid = attrs["s"]?.let { String(it) }?.toLongOrNull()
                    val gid = attrs["g"]?.let { String(it) }.orEmpty()
                    val proto = attrs["v"]?.let { String(it) }?.toIntOrNull() ?: 0
                    if (host is Inet4Address && sid != null && (gameId == null || gid == gameId) && !games.containsKey(sid)) {
                        games[sid] = LanGame(sid, gid, serviceInfo.serviceName.removePrefix("OEA "), attrs["h"]?.let { String(it) } ?: "Host",
                            host.hostAddress.orEmpty(), serviceInfo.port, 0, 0, true, proto, System.currentTimeMillis())
                        publish()
                    }
                    OeaGuard.post("lan-discovery", main) { resolving = false; resolveNext() }
                }
            })
        }.onFailure { resolving = false }
    }

    fun stop() {
        running = false
        main.removeCallbacks(expiry)
        runCatching { socket?.close() }
        socket = null
        nsdListener?.let { l -> runCatching { context.getSystemService(NsdManager::class.java)?.stopServiceDiscovery(l) } }
        nsdListener = null
        runCatching { lock?.release() }
        lock = null
        resolveQueue.clear()
    }
}

/**
 * Compact LAN join code ("OEAL-…", also shown as a QR): host addresses, port,
 * session and game. Lets a friend join instantly even if discovery is blocked.
 */
object LanJoinCode {
    private const val PREFIX = "OEAL-"
    private const val VERSION = 1

    data class Target(val addresses: List<String>, val port: Int, val sessionId: Long, val gameId: String)

    fun encode(addresses: List<Inet4Address>, port: Int, sessionId: Long, gameId: String): String {
        val w = ByteWriter(64).u8(VERSION).i64(sessionId).u16(port).u8(addresses.size.coerceAtMost(4))
        addresses.take(4).forEach { w.bytes(it.address) }
        w.string(gameId)
        val body = w.toByteArray()
        val crc = Codes.crc32(body)
        val all = ByteWriter(body.size + 4).bytes(body).i32(crc).toByteArray()
        return PREFIX + Codes.base32(all)
    }

    fun isLanCode(text: String) = text.trim().uppercase().replace(" ", "").startsWith(PREFIX)

    /** Returns the target or a readable error. */
    fun decode(text: String): Result<Target> {
        val cleaned = text.trim().uppercase().replace(" ", "")
        if (!cleaned.startsWith(PREFIX)) return Result.failure(IllegalArgumentException("That isn't an OEA Wi-Fi code."))
        val raw = Codes.base32Decode(cleaned.removePrefix(PREFIX)) ?: return Result.failure(IllegalArgumentException("The code contains invalid characters."))
        if (raw.size < 8) return Result.failure(IllegalArgumentException("The code is incomplete."))
        val crc = ByteReader(raw, raw.size - 4).i32()
        if (Codes.crc32(raw, 0, raw.size - 4) != crc) return Result.failure(IllegalArgumentException("The code is incomplete or was changed while copying."))
        return runCatching {
            val r = ByteReader(raw, 0, raw.size - 4)
            if (r.u8() != VERSION) throw IllegalArgumentException("This code is from a different OEA version.")
            val sid = r.i64()
            val port = r.u16()
            val n = r.u8()
            val addrs = (0 until n).map { InetAddress.getByAddress(r.bytes(4)).hostAddress.orEmpty() }
            Target(addrs, port, sid, r.string(64))
        }
    }
}
