package com.oea.launcher.privilege

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect
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
 * Captured values feed OEA's embedded ADB pairing flow. Ambiguous endpoints
 * are rejected rather than guessed.
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

        val entries = ArrayList<Entry>()
        collectEntries(root, entries, 0)
        if (entries.isEmpty()) return
        val changed = readEntries(entries, now)
        if (changed) OeaAssistant.notifyStateChanged()
        if (freshPairing() != null && connection != null) {
            // Both values are in hand: stop watching so nothing else is read.
            watchUntil = 0L
            OeaAssistant.refreshEventMask()
        }
    }

    /** A text item and its vertical bounds in the Settings accessibility tree. */
    data class Entry(val text: String, val top: Int, val bottom: Int) {
        val center: Int get() = (top + bottom) / 2
    }

    /** Compatibility/test entry point; production uses real screen bounds below. */
    internal fun read(texts: List<String>, now: Long): Boolean =
        readEntries(texts.mapIndexed { index, text -> Entry(text, index * 100, index * 100 + 40) }, now)

    /**
     * Pairing dialogs overlay the main Wireless debugging page, so both IP:port
     * values may appear in one accessibility tree. Match each address to the
     * nearest code or main-page label rather than rejecting the whole tree.
     */
    internal fun readEntries(entries: List<Entry>, now: Long): Boolean {
        val codeEntry = entries.firstOrNull { codeRe.matches(it.text.trim()) }
        val addresses = entries.mapNotNull { entry ->
            addressRe.find(entry.text)?.let { match ->
                val octets = match.groupValues[1].split('.').mapNotNull { it.toIntOrNull() }
                val port = match.groupValues[2].toIntOrNull()
                if (octets.size != 4 || octets.any { it !in 0..255 } || port == null || port !in 1..65535) null
                else Triple(entry, match.groupValues[1], port)
            }
        }
        var changed = false

        if (codeEntry != null && addresses.isNotEmpty()) {
            val endpoint = addresses.minByOrNull { kotlin.math.abs(it.first.center - codeEntry.center) }
            if (endpoint != null) {
                val code = codeRe.matchEntire(codeEntry.text.trim())!!.let { it.groupValues[1] + it.groupValues[2] }
                val (_, host, port) = endpoint
                val current = pairing
                if (current == null || current.code != code || current.host != host || current.port != port) {
                    pairing = Pairing(code, host, port, now)
                    changed = true
                }
            }
        }

        val mainLabel = entries.firstOrNull {
            it.text.trim().equals("Wireless debugging", ignoreCase = true) ||
                it.text.trim().equals("Wireless debugging settings", ignoreCase = true)
        }
        if (mainLabel != null && addresses.isNotEmpty()) {
            val endpoint = addresses.minByOrNull { kotlin.math.abs(it.first.center - mainLabel.center) }
            if (endpoint != null) {
                val (_, host, port) = endpoint
                val current = connection
                if (current == null || current.port != port || current.host != host) {
                    connection = Connection(host, port, now)
                    changed = true
                }
            }
        }
        return changed
    }

    private fun collectEntries(node: AccessibilityNodeInfo, out: MutableList<Entry>, depth: Int) {
        if (depth > 24 || out.size > 400) return
        val bounds = Rect()
        runCatching { node.getBoundsInScreen(bounds) }
        fun add(value: CharSequence?) {
            value?.toString()?.takeIf { it.isNotBlank() }?.let {
                out += Entry(it, bounds.top, bounds.bottom)
            }
        }
        add(node.text)
        add(node.contentDescription)
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            collectEntries(child, out, depth + 1)
        }
    }

}
