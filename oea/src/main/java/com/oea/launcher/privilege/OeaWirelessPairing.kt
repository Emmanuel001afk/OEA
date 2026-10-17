package com.oea.launcher.privilege

import android.content.Context
import android.graphics.Rect
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.oea.launcher.assistant.OeaAssistant
import com.oea.launcher.privilege.adb.AdbConnection
import com.oea.launcher.privilege.adb.AdbKeys
import com.oea.launcher.privilege.adb.PairingConnection
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Reads the Wireless debugging screens for Access Doctor, and performs the
 * actual wireless pairing with them.
 *
 * While "armed", OEA Assistant (the Accessibility service) looks at Settings
 * windows and reads three things by itself:
 *  - the 6-digit pairing code and the pairing address (ip:port) from the
 *    "Pair device with pairing code" dialog,
 *  - the connection port from the Wireless debugging screen ("IP address & Port").
 *
 * So the user never types them. Typing is only a fallback, and it lives in
 * Access Doctor. Values are kept in memory only and expire: a pairing code is
 * valid only while its dialog is open, so it is dropped after two minutes, and
 * the whole watch stops after five minutes or as soon as both values are read.
 *
 * Once the values are captured, [executePairing] performs the real handshake
 * with the phone's adbd — the same one the desktop's `adb pair` runs: TLS 1.3
 * with OEA's ADB identity, SPAKE2 over the 6-digit code, and an encrypted
 * PeerInfo exchange whose payload (OEA's ADB public key) is what authorizes
 * this install. Afterwards [connectAdb] opens the TLS-secured ADB connection
 * on the connection port, ready for shell/sync streams.
 */
object OeaWirelessPairing {

    data class Pairing(val code: String, val host: String, val port: Int, val capturedAt: Long, val manual: Boolean = false)
    data class Connection(val host: String, val port: Int, val capturedAt: Long, val manual: Boolean = false)

    /** Where the pairing run got to, for the Access Doctor card. */
    sealed class Outcome {
        object Idle : Outcome()
        object Running : Outcome()
        data class Paired(val device: String, val at: Long) : Outcome()
        data class Connected(val device: String, val banner: String, val at: Long) : Outcome()
        data class Failed(val message: String, val at: Long) : Outcome()
    }

    private const val WATCH_MS = 5 * 60_000L
    private const val CODE_TTL_MS = 2 * 60_000L
    private const val SCAN_GAP_MS = 150L
    private val addressRe = Regex("""(\d{1,3}(?:\.\d{1,3}){3}):(\d{2,5})""")
    private val codeRe = Regex("""^\s*(\d{3})\s?(\d{3})\s*$""")

    @Volatile var pairing: Pairing? = null
        private set
    @Volatile var connection: Connection? = null
        private set
    @Volatile var outcome: Outcome = Outcome.Idle
        private set
    @Volatile private var watchUntil = 0L
    @Volatile private var lastScan = 0L
    @Volatile private var adbKeys: AdbKeys? = null
    @Volatile private var adbSession: AdbConnection? = null
    @Volatile private var adbSessionAt = 0L
    @Volatile private var appContext: Context? = null
    @Volatile private var lastAutoPairKey: String? = null
    @Volatile private var lastAutoPairAt = 0L
    @Volatile private var lastPairedKey: String? = null
    @Volatile private var nsdResolving = false
    private var nsdDiscovery: NsdManager.DiscoveryListener? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    internal class Entry(val text: String, val top: Int, val bottom: Int)
    private class Address(val host: String, val port: Int, val entry: Entry)

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val SOCKET_TIMEOUT_MS = 15_000

    fun isWatching(): Boolean = SystemClock.elapsedRealtime() < watchUntil

    fun outcomeText(): String = when (val o = outcome) {
        is Outcome.Idle -> "Not paired yet"
        is Outcome.Running -> "Pairing…"
        is Outcome.Paired -> "Paired with the phone${if (o.device.isNotBlank()) " ($o.device)" else ""}"
        is Outcome.Connected -> "Connected over Wi-Fi (${o.banner})"
        is Outcome.Failed -> "Failed: ${o.message}"
    }

    fun isAdbConnected(): Boolean {
        val session = adbSession ?: return false
        return SystemClock.elapsedRealtime() - adbSessionAt < 60_000L && !session.isClosed()
    }

    /**
     * Runs the real pairing with the captured values: TLS 1.3 + SPAKE2 with the
     * 6-digit code against the phone's pairing port, then — when the connection
     * port is known — the TLS ADB connection. Safe to call twice; runs on its
     * own thread and reports through [outcome].
     */
    fun attach(context: Context) { appContext = context.applicationContext }

    @Synchronized
    fun executePairing(context: Context) {
        attach(context)
        val target = freshPairing() ?: run {
            outcome = Outcome.Failed("No pairing code has been read yet", SystemClock.elapsedRealtime())
            OeaAssistant.notifyStateChanged()
            return
        }
        if (outcome is Outcome.Running) return
        outcome = Outcome.Running
        OeaAssistant.notifyStateChanged()
        Thread({
            try {
                val keys = adbKeys ?: AdbKeys.loadOrCreate(adbDir(context)).also { adbKeys = it }
                // 1) the pairing handshake on the pairing port
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MS)
                    socket.soTimeout = SOCKET_TIMEOUT_MS
                    val pairingConnection = PairingConnection(socket, keys, target.code)
                    val device = pairingConnection.pair()
                    lastPairedKey = "${target.code}:${target.host}:${target.port}"
                    outcome = Outcome.Paired(device, SystemClock.elapsedRealtime())
                }
                OeaAssistant.notifyStateChanged()
                if (connection != null) finishConnect() else discoverConnectionPort()
            } catch (e: Exception) {
                val raw = e.message ?: e.javaClass.simpleName
                val message = when {
                    raw.contains("ECONNREFUSED", true) || raw.contains("Connection refused", true) -> "Pairing dialog closed. Reopen it; OEA will retry automatically."
                    raw.contains("timeout", true) || raw.contains("ETIMEDOUT", true) -> "No response from ${target.host}:${target.port}. Keep the pairing dialog open and use the same Wi-Fi network."
                    else -> raw
                }
                outcome = Outcome.Failed(message, SystemClock.elapsedRealtime())
                OeaAssistant.notifyStateChanged()
            }
        }, "oea-wireless-pairing").start()
    }

    /**
     * Opens the TLS-protected ADB connection on the connection port. Needs a
     * completed pairing (the phone must already hold OEA's public key).
     */
    @Synchronized
    fun connectAdb() {
        val keys = adbKeys ?: throw IllegalStateException("Pair first")
        val target = connection ?: throw IllegalStateException("No connection port has been read yet")
        adbSession?.close()
        adbSession = null
        val socket = Socket()
        socket.connect(InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MS)
        socket.soTimeout = SOCKET_TIMEOUT_MS
        val adb = AdbConnection(socket, keys)
        val banner = adb.connect()
        adbSession = adb
        adbSessionAt = SystemClock.elapsedRealtime()
        outcome = Outcome.Connected(
            (outcome as? Outcome.Paired)?.device ?: (outcome as? Outcome.Connected)?.device ?: "",
            "v${banner.version shr 24}.${banner.version and 0xFFFFFF}, ${banner.maxPayload / 1024} KiB",
            adbSessionAt)
        OeaAssistant.notifyStateChanged()
    }

    private fun finishConnect() {
        try { connectAdb() } catch (e: Exception) {
            outcome = Outcome.Failed("Paired, but ADB connection failed: ${e.message ?: e.javaClass.simpleName}", SystemClock.elapsedRealtime())
            OeaAssistant.notifyStateChanged()
        }
    }

    fun discoverConnectionPort() {
        val context = appContext ?: return
        if (connection != null) { finishConnect(); return }
        if (nsdDiscovery != null) return
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val expectedHost = pairing?.host
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onServiceFound(info: NsdServiceInfo?) {
                val service = info ?: return
                if (nsdResolving || connection != null || service.serviceType?.contains("_adb-tls-connect._tcp") != true) return
                nsdResolving = true
                try {
                    manager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) { nsdResolving = false }
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                            nsdResolving = false
                            val resolved = serviceInfo ?: return
                            val host = resolved.host?.hostAddress ?: return
                            if (expectedHost != null && host != expectedHost) return
                            if (resolved.port !in 1..65535 || connection != null) return
                            connection = Connection(host, resolved.port, SystemClock.elapsedRealtime())
                            OeaAssistant.notifyStateChanged()
                            stopNsd()
                            Thread({ finishConnect() }, "oea-adb-connect").start()
                        }
                    })
                } catch (_: Exception) { nsdResolving = false }
            }
            override fun onServiceLost(info: NsdServiceInfo?) {}
            override fun onDiscoveryStopped(serviceType: String?) { nsdDiscovery = null }
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) { nsdDiscovery = null }
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) { nsdDiscovery = null }
        }
        nsdDiscovery = listener
        try { manager.discoverServices("_adb-tls-connect._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }
        catch (_: Exception) { nsdDiscovery = null; return }
        mainHandler.postDelayed({ stopNsd() }, 30_000L)
    }

    private fun stopNsd() {
        val listener = nsdDiscovery ?: return
        nsdDiscovery = null
        val manager = appContext?.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        try { manager.stopServiceDiscovery(listener) } catch (_: Exception) {}
    }

    /** Runs a shell command on the phone over the connected ADB session. */
    fun shell(command: String, timeoutMs: Long = 10_000L): String {
        val session = adbSession ?: throw IllegalStateException("Not connected")
        return session.shell(command, timeoutMs)
    }

    fun closeAdb() {
        adbSession?.close()
        adbSession = null
    }

    private fun adbDir(context: Context): File = File(context.filesDir, "adb").apply { mkdirs() }

    /** Starts watching. The caller opens the Wireless debugging screen. */
    fun startWatching() {
        watchUntil = SystemClock.elapsedRealtime() + WATCH_MS
        OeaAssistant.refreshEventMask()
        OeaAssistant.notifyStateChanged()
    }

    fun stopWatching() {
        watchUntil = 0L
        OeaAssistant.refreshEventMask()
        OeaAssistant.notifyStateChanged()
    }

    /** The pairing code, if one was read and is still fresh. */
    fun freshPairing(): Pairing? = pairing?.takeIf { SystemClock.elapsedRealtime() - it.capturedAt < CODE_TTL_MS || it.manual }

    fun clear() {
        pairing = null
        connection = null
        closeAdb()
        outcome = Outcome.Idle
        lastAutoPairKey = null
        lastPairedKey = null
        stopNsd()
        OeaAssistant.notifyStateChanged()
    }

    /** Fallback: the user typed the values because automatic reading did not find them. */
    fun setManual(code: String?, pairingAddress: String?, connectionPort: Int?) {
        val now = SystemClock.elapsedRealtime()
        val addr = pairingAddress?.let { addressRe.find(it.trim()) }
        if (code != null && addr != null && codeRe.matches(code)) {
            val digits = code.filter { it.isDigit() }
            pairing = Pairing(digits, addr.groupValues[1], addr.groupValues[2].toInt(), now, manual = true)
        }
        if (connectionPort != null && connectionPort in 1..65535) {
            val host = connection?.host ?: pairing?.host ?: ""
            connection = Connection(host, connectionPort, now, manual = true)
        }
        OeaAssistant.notifyStateChanged()
    }

    /**
     * Called by the Accessibility service for each window event while watching.
     * Cheap: throttled, and it only reads Settings windows.
     */
    fun scan(root: AccessibilityNodeInfo?, packageName: String?) {
        if (!isWatching() || root == null) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastScan < SCAN_GAP_MS) return
        lastScan = now
        if (packageName == null || !packageName.contains("settings", ignoreCase = true)) return
        val entries = ArrayList<Entry>()
        collect(root, entries, 0)
        if (entries.isEmpty()) return
        if (read(entries, now)) OeaAssistant.notifyStateChanged()
        maybeAutoPair()
        if (pairing != null && connection != null && outcome !is Outcome.Running && outcome !is Outcome.Failed) {
            watchUntil = 0L
            OeaAssistant.refreshEventMask()
        }
    }

    private fun maybeAutoPair() {
        val context = appContext ?: return
        val p = freshPairing() ?: return
        val key = "${p.code}:${p.host}:${p.port}"
        if (key == lastPairedKey || outcome is Outcome.Running || outcome is Outcome.Paired || outcome is Outcome.Connected) return
        if (key == lastAutoPairKey && SystemClock.elapsedRealtime() - lastAutoPairAt < 5_000L) return
        lastAutoPairKey = key
        lastAutoPairAt = SystemClock.elapsedRealtime()
        executePairing(context)
    }

    internal fun read(texts: List<String>, now: Long): Boolean = read(texts.map { Entry(it, 0, 0) }, now)
    internal fun read(entries: List<Entry>, now: Long): Boolean {
        val codeEntry = entries.firstOrNull { codeRe.matchEntire(it.text.trim()) != null }
        val code = codeEntry?.let { codeRe.matchEntire(it.text.trim())!!.let { m -> m.groupValues[1] + m.groupValues[2] } }
        val addresses = entries.mapNotNull { e -> addressRe.find(e.text)?.let { Address(it.groupValues[1], it.groupValues[2].toInt(), e) } }
        val mainScreen = entries.any { it.text.equals("Wireless debugging", ignoreCase = true) }
        var changed = false
        if (code != null) {
            val chosen = when {
                addresses.isEmpty() -> null
                addresses.size == 1 -> addresses[0]
                else -> addresses.minByOrNull { distance(it.entry, codeEntry!!) }
            }
            if (chosen != null) {
                val p = pairing
                if (p == null || p.code != code || p.host != chosen.host || p.port != chosen.port) { pairing = Pairing(code, chosen.host, chosen.port, now); changed = true }
                if (mainScreen && addresses.size > 1) {
                    val other = addresses.firstOrNull { it !== chosen && (it.host != chosen.host || it.port != chosen.port) }
                    if (other != null) {
                        val c = connection
                        if (c == null || c.host != other.host || c.port != other.port) { connection = Connection(other.host, other.port, now); changed = true }
                    }
                }
            }
        } else if (mainScreen && addresses.isNotEmpty()) {
            val first = addresses.first()
            val c = connection
            if (c == null || c.host != first.host || c.port != first.port) { connection = Connection(first.host, first.port, now); changed = true }
        }
        return changed
    }

    private fun distance(a: Entry, b: Entry): Int = kotlin.math.abs((a.top + a.bottom) / 2 - (b.top + b.bottom) / 2)
    private fun collect(node: AccessibilityNodeInfo, out: MutableList<Entry>, depth: Int) {
        if (depth > 24 || out.size > 400) return
        val rect = Rect(); node.getBoundsInScreen(rect)
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out += Entry(it, rect.top, rect.bottom) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out += Entry(it, rect.top, rect.bottom) }
        for (i in 0 until node.childCount) { val child = node.getChild(i) ?: continue; collect(child, out, depth + 1) }
    }
}
