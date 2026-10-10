package com.oea.launcher.applock

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * OEA-owned shell-side package suspension bridge.
 *
 * Android starts this class under the ADB shell UID (not the OEA app UID).
 * It deliberately listens only on loopback and requires the per-install token
 * supplied by OEA. It does not grant OEA permanent system privileges.
 */
object OeaFreezerShellBridge {
    private const val DEFAULT_PORT = 39742

    @JvmStatic
    fun main(args: Array<String>) {
        val port = args.getOrNull(0)?.toIntOrNull() ?: DEFAULT_PORT
        val token = args.getOrNull(1)?.takeIf { it.length >= 24 } ?: return
        val server = ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 0
        while (!server.isClosed) {
            val client = runCatching { server.accept() }.getOrNull() ?: continue
            Thread({ handle(client, token) }, "OEA-Freezer-Client").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handle(socket: Socket, expectedToken: String) {
        socket.use { client ->
            client.soTimeout = 4_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val writer = PrintWriter(client.getOutputStream(), true)
            val fields = reader.readLine()?.split('\t') ?: return
            if (fields.size != 3 || fields[0] != expectedToken) {
                writer.println("ERROR\tAuthentication failed")
                return
            }
            val action = fields[1]
            val packageName = fields[2]
            if (!packageName.matches(Regex("[A-Za-z0-9_.]+")) ||
                packageName == "com.oea.launcher" ||
                action !in setOf("SUSPEND", "UNSUSPEND", "STATUS")) {
                writer.println("ERROR\tInvalid request")
                return
            }
            if (action != "STATUS") {
                val verb = if (action == "SUSPEND") "suspend" else "unsuspend"
                val result = runCommand("cmd", "package", verb, "--user", "0", packageName)
                if (!result.first) {
                    writer.println("ERROR\t" + clean(result.second.ifBlank { "Android package command failed" }))
                    return
                }
            }
            val state = querySuspended(packageName)
            if (state == null) {
                writer.println("ERROR\tCould not verify Android package state")
            } else {
                writer.println("OK\t" + if (state) "FROZEN" else "ACTIVE")
            }
        }
    }

    private fun querySuspended(packageName: String): Boolean? {
        val (ok, output) = runCommand("dumpsys", "package", packageName)
        if (!ok) return null
        val userLine = Regex("(?m)^\\s*User 0:.*$").find(output)?.value ?: return null
        return Regex("\\bsuspended=true\\b").containsMatchIn(userLine)
    }

    private fun runCommand(vararg command: String): Pair<Boolean, String> = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(5, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            false to "Android command timed out"
        } else {
            (process.exitValue() == 0) to output
        }
    }.getOrElse { false to (it.message ?: "Could not run Android shell command") }

    private fun clean(value: String): String = value.replace('\t', ' ').replace('\n', ' ').take(180)
}
