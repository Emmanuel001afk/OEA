package com.oea.launcher.privilege

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.oea.launcher.assistant.OeaAssistant

/**
 * Reads the Wireless debugging screens for Access Doctor.
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
 * Nothing here talks to ADB. The captured values are the input for the pairing
 * step, which is not part of this build.
 */
object OeaWirelessPairing {

    data class Pairing(val code: String, val host: String, val port: Int, val capturedAt: Long, val manual: Boolean = false)
    data class Connection(val host: String, val port: Int, val capturedAt: Long, val manual: Boolean = false)

    private const val WATCH_MS = 5 * 60_000L
    private const val CODE_TTL_MS = 2 * 60_000L
    private const val SCAN_GAP_MS = 150L
    private val addressRe = Regex("""(\d{1,3}(?:\.\d{1,3}){3}):(\d{2,5})""")
    private val codeRe = Regex("""^\s*(\d{3})\s?(\d{3})\s*$""")

    @Volatile var pairing: Pairing? = null
        private set
    @Volatile var connection: Connection? = null
        private set
    @Volatile private var watchUntil = 0L
    @Volatile private var lastScan = 0L

    fun isWatching(): Boolean = SystemClock.elapsedRealtime() < watchUntil

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
        // Only Settings screens: the values live there, nothing else is read.
        if (packageName == null || !packageName.contains("settings", ignoreCase = true)) return

        val texts = ArrayList<String>()
        collect(root, texts, 0)
        if (texts.isEmpty()) return
        val changed = read(texts, now)
        if (changed) OeaAssistant.notifyStateChanged()
        if (pairing != null && connection != null) {
            // Both values are in hand: stop watching so nothing else is read.
            watchUntil = 0L
            OeaAssistant.refreshEventMask()
        }
    }

    /** Pure parsing step (no Android types), so it can be unit-tested on the JVM. */
    internal fun read(texts: List<String>, now: Long): Boolean {
        val code = texts.firstNotNullOfOrNull { t -> codeRe.matchEntire(t.trim())?.let { it.groupValues[1] + it.groupValues[2] } }
        val addresses = texts.mapNotNull { t -> addressRe.find(t)?.let { m -> m.groupValues[1] to m.groupValues[2].toInt() } }
        // The pairing dialog is its own window, so it does not contain the screen's
        // "Wireless debugging" title. The main screen always does, and shows the
        // connection address; its "Pair device with pairing code" row is not a code.
        val mainScreen = texts.any { it.equals("Wireless debugging", ignoreCase = true) }
        var changed = false

        if (code != null && addresses.isNotEmpty()) {
            // The pairing dialog shows the code together with the pairing address.
            val (host, port) = addresses.first()
            val current = pairing
            if (current == null || current.code != code || current.host != host || current.port != port) {
                pairing = Pairing(code, host, port, now)
                changed = true
            }
        }
        if (mainScreen && code == null && addresses.isNotEmpty()) {
            // The Wireless debugging screen shows the connection address and port.
            val (host, port) = addresses.first()
            val current = connection
            if (current == null || current.port != port || current.host != host) {
                connection = Connection(host, port, now)
                changed = true
            }
        }
        return changed
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > 24 || out.size > 400) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, out, depth + 1)
        }
    }
}
