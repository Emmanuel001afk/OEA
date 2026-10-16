package com.oea.launcher.privilege

import android.content.Context
import android.os.Build
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import okio.Path.Companion.toPath
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OEA-owned Wireless Debugging bridge.
 *
 * Pairing is performed by OEA itself using the Android Wireless Debugging
 * pairing endpoint and one-time code. Android must already have Wireless
 * Debugging enabled and the user must approve/authorize the pairing.
 *
 * Only a small allowlist of OEA operations is exposed to callers. This is not
 * an arbitrary shell/terminal and does not silently enable Android debugging.
 */
object OeaAdbAuthority {
    private const val PREFS = "oea_adb_authority"
    private const val KEY_HOST = "host"
    private const val KEY_CONNECT_PORT = "connect_port"
    private const val MAX_OUTPUT = 256 * 1024
    private val sessionReady = AtomicBoolean(false)
    private val identityLock = Any()
    @Volatile private var identityPath: String? = null

    data class Status(
        val configured: Boolean,
        val connected: Boolean,
        val host: String?,
        val port: Int?,
        val note: String
    )

    fun status(context: Context): Status {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_HOST, null)
        val port = prefs.getInt(KEY_CONNECT_PORT, -1).takeIf { it in 1..65535 }
        val configured = !host.isNullOrBlank() && port != null
        val connected = configured && sessionReady.get()
        return Status(
            configured, connected, host, port,
            when {
                connected -> "Wireless Debugging bridge connected and shell-verified."
                configured -> "A paired endpoint is saved. Reconnect to verify it is reachable."
                else -> "Not paired. Enable Wireless Debugging, then pair OEA using the code shown by Android."
            }
        )
    }

    /**
     * Pair with Android's temporary pairing port, then verify a shell connection
     * on the separate Wireless Debugging connection port. Never saves the code.
     */
    suspend fun pairAndConnect(
        context: Context,
        host: String,
        pairingPort: Int,
        pairingCode: String,
        connectPort: Int
    ): String = withContext(Dispatchers.IO) {
        val safeHost = validateHost(host)
        require(pairingPort in 1..65535) { "Pairing port must be between 1 and 65535." }
        require(connectPort in 1..65535) { "Connection port must be between 1 and 65535." }
        require(pairingCode.matches(Regex("\\d{6}"))) { "Enter the six-digit pairing code currently shown by Android." }
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            "OEA's embedded Wireless Debugging bridge requires Android 9 or newer."
        }

        ensureIdentity(context.applicationContext)
        // Fail quickly on a stale or mistyped endpoint before beginning TLS pairing.
        Socket().use { socket ->
            socket.connect(InetSocketAddress(safeHost, pairingPort), 3_500)
        }
        Kadb.pair(safeHost, pairingPort, pairingCode)

        // Pairing succeeded. Save only the endpoint (never the one-time code) so
        // a connection-port typo can be corrected and retried without re-pairing.
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_HOST, safeHost).putInt(KEY_CONNECT_PORT, connectPort).apply()
        sessionReady.set(false)

        val probe = connectAndProbe(safeHost, connectPort)
        if (!probe.first) {
            throw IllegalStateException(
                "Pairing succeeded, but the shell connection was not verified: ${probe.second}. " +
                    "Check the separate connection port on the main Wireless Debugging screen, then tap Reconnect."
            )
        }
        sessionReady.set(true)
        "Paired and verified. OEA shell identity: ${probe.second.trim()}"
    }

    suspend fun reconnect(context: Context): String = withContext(Dispatchers.IO) {
        ensureIdentity(context.applicationContext)
        val saved = status(context)
        val host = saved.host ?: throw IllegalStateException("No saved endpoint. Pair OEA first.")
        val port = saved.port ?: throw IllegalStateException("No saved connection port. Pair OEA first.")
        sessionReady.set(false)
        val probe = connectAndProbe(host, port)
        if (!probe.first) {
            throw IllegalStateException("Could not reconnect to $host:$port: ${probe.second}")
        }
        sessionReady.set(true)
        "Connected and verified. OEA shell identity: ${probe.second.trim()}"
    }

    fun forget(context: Context) {
        sessionReady.set(false)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** True only after a real ADB shell identity probe succeeded in this process. */
    fun isSessionReady(): Boolean = sessionReady.get()

    /**
     * Runs only the small command set required by OEA's freezer and verification.
     * It is synchronous for existing worker-thread freezer APIs; never call from
     * Home's main thread.
     */
    fun runShell(command: String): Pair<Boolean, String> {
        if (!sessionReady.get()) return false to "OEA Wireless Debugging bridge is not connected. Open OEA Authority and reconnect."
        if (!isAllowedCommand(command)) return false to "OEA refused an unsupported shell command."
        val savedHost = cachedHost ?: return false to "OEA bridge endpoint is not available."
        val savedPort = cachedPort ?: return false to "OEA bridge endpoint is not available."
        return runCatching {
            Kadb.create(savedHost, savedPort, connectTimeout = 3_500, socketTimeout = 5_000).use { adb ->
                val response = adb.shell(command)
                val output = response.allOutput.take(MAX_OUTPUT).trim()
                val ok = response.exitCode == 0
                ok to (output.ifBlank { response.errorOutput.take(MAX_OUTPUT).trim() })
            }
        }.getOrElse {
            sessionReady.set(false)
            false to (it.message ?: "Wireless Debugging command failed.")
        }
    }

    private var cachedHost: String? = null
    private var cachedPort: Int? = null

    private fun ensureIdentity(context: Context) {
        val file = File(context.filesDir, "oea-adb-host-key.pem")
        val path = file.absolutePath
        synchronized(identityLock) {
            if (identityPath != path) {
                // Kadb defaults to an in-memory key store; persist the host key
                // privately so Android continues to trust OEA after process death.
                KadbCert.configure(OkioFilePrivateKeyStore(path.toPath()))
                KadbCert.ensureReady()
                identityPath = path
            }
        }
    }

    private fun connectAndProbe(host: String, port: Int): Pair<Boolean, String> {
        val result = runCatching {
            Kadb.create(host, port, connectTimeout = 3_500, socketTimeout = 5_000).use { adb ->
                val response = adb.shell("id")
                val output = response.allOutput.take(4096).trim()
                if (response.exitCode != 0 || !output.contains("uid=2000") && !output.contains("uid=0")) {
                    false to (output.ifBlank { response.errorOutput.take(4096).trim() }.ifBlank { "Unexpected shell identity or non-zero exit." })
                } else {
                    cachedHost = host
                    cachedPort = port
                    true to output
                }
            }
        }.getOrElse { false to (it.message ?: it.javaClass.simpleName) }
        if (!result.first) {
            cachedHost = null
            cachedPort = null
        }
        return result
    }

    private fun validateHost(raw: String): String {
        val host = raw.trim()
        require(host.isNotEmpty() && host.length <= 253 &&
            host.matches(Regex("[A-Za-z0-9][A-Za-z0-9.:%_-]*"))) {
            "Enter the phone's local IP address or host name without a scheme."
        }
        return host
    }

    private fun isAllowedCommand(command: String): Boolean {
        if (command == "id" || command == "dumpsys package" ||
            command == "settings put secure accessibility_enabled 1") return true
        val packageName = "[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"
        if (Regex("cmd package (?:suspend|unsuspend) --user 0 $packageName").matches(command) ||
            Regex("dumpsys package $packageName").matches(command)) return true

        // Permission-repair commands are scoped to OEA's own package only.
        if (Regex("appops set com\\.oea\\.launcher (?:SYSTEM_ALERT_WINDOW|GET_USAGE_STATS) allow").matches(command)) return true
        if (Regex("dumpsys deviceidle whitelist \\+com\\.oea\\.launcher").matches(command)) return true
        if (Regex("cmd package set-home-activity com\\.oea\\.launcher/com\\.oea\\.launcher\\.[A-Za-z0-9_.]+").matches(command)) return true
        if (Regex("pm grant com\\.oea\\.launcher android\\.permission\\.[A-Z0-9_]+").matches(command)) return true

        val accessibilityPrefix = "settings put secure enabled_accessibility_services "
        val notificationPrefix = "settings put secure enabled_notification_listeners "
        if (command.startsWith(accessibilityPrefix)) {
            val rest = command.removePrefix(accessibilityPrefix)
            val pieces = rest.split(" && ")
            if (pieces.size !in 1..2) return false
            if (pieces.size == 2 && pieces[1] != "settings put secure accessibility_enabled 1") return false
            if (pieces[0].contains(" && ")) return false
            return validServiceList(pieces[0])
        }
        if (command.startsWith(notificationPrefix)) {
            return validServiceList(command.removePrefix(notificationPrefix))
        }
        return false
    }

    private fun validServiceList(value: String): Boolean {
        if (!value.matches(Regex("[A-Za-z0-9_./:+-]+"))) return false
        return value.split(':').any { it.startsWith("com.oea.launcher/") }
    }
}
