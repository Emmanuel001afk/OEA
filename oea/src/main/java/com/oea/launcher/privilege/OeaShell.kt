package com.oea.launcher.privilege

import android.util.Log
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * OEA's own privileged channel.
 *
 * Some features (freezing apps without root, granting OEA a permission Android
 * only lets the user grant) need shell authority. The usual answer is a second
 * app (Shizuku) or a PC (adb). OEA instead uses the authority the device already
 * has, and nothing else:
 *
 * - **Device owner**: Android's own management role. If OEA is the device owner
 *   it may grant *runtime* permissions to itself through [android.app.admin.DevicePolicyManager].
 * - **Root (`su`)**: when the device has a working su binary, OEA runs the
 *   handful of `appops`, `settings`, `pm` and `cmd` commands its own features
 *   need. This is the same mechanism a helper app uses, without the helper app.
 *
 * Rules, both to stay safe and to stay honest:
 * - Commands are built from OEA's own package name and a fixed set of verbs.
 *   Nothing user-typed is ever passed to a shell (see [sanitizeTarget]).
 * - Every write is **verified by reading the state back**; a command that
 *   reports success but changes nothing is reported as a failure.
 * - Output is capped and every call has a timeout, so a hung su can never
 *   freeze the launcher or fill the heap.
 */
object OeaShell {
    private const val TAG = "OeaShell"
    private const val PROBE_TIMEOUT_MS = 900L
    private const val DEFAULT_TIMEOUT_MS = 5_000L
    private const val MAX_OUTPUT = 512 * 1024

    private val lock = Any()
    @Volatile private var probed = false
    @Volatile private var hasSu = false
    @Volatile var lastError: String? = null
        private set

    /** Result of one shell command. */
    data class Output(val ok: Boolean, val text: String, val exit: Int) {
        val failed get() = !ok
        operator fun component1() = ok
        operator fun component2() = text
    }

    /** True when a su binary answers on this device. Probed once per process. */
    fun available(): Boolean {
        if (probed) return hasSu
        synchronized(lock) {
            if (probed) return hasSu
            hasSu = runCatching {
                val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
                val finished = process.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    false
                } else {
                    val out = runCatching { process.inputStream.readBytes().toString(Charsets.UTF_8) }.getOrDefault("")
                    process.exitValue() == 0 && out.contains("uid=0")
                }
            }.getOrDefault(false)
            probed = true
            if (!hasSu) lastError = null
            else Log.i(TAG, "Root channel available")
            return hasSu
        }
    }

    /** Drops the cached probe result, so a just-installed su is noticed without a reboot. */
    fun forgetProbe() = synchronized(lock) {
        probed = false
        hasSu = false
    }

    /**
     * Runs [command] as root with a hard timeout and output cap. Never throws:
     * a missing su, a denied grant and a crash all come back as a failed [Output].
     */
    fun run(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Output {
        if (!available()) return Output(false, "This device has no root access, so OEA cannot run the command.", -1)
        return runCatching {
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val captured = AtomicReference("")
            val reader = Thread {
                // Drain while the command runs: a full pipe buffer would deadlock su.
                captured.set(readCapped(process.inputStream))
            }.apply { isDaemon = true; start() }
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                runCatching { process.destroyForcibly() }
                lastError = "The command took too long and was stopped."
                return Output(false, lastError!!, -2)
            }
            runCatching { reader.join(250) }
            val output = captured.get().take(MAX_OUTPUT).trim()
            val ok = process.exitValue() == 0
            if (!ok) lastError = output.ifBlank { "The command failed." }
            Output(ok, output, process.exitValue())
        }.getOrElse { error ->
            lastError = error.message ?: "Root command failed"
            Output(false, lastError!!, -3)
        }
    }

    /**
     * Runs [command] then polls [check] until Android reports the wanted state.
     * Android sometimes applies an appops or secure-settings change a moment
     * after the command returns, so one immediate read is not proof.
     */
    fun applyAndVerify(command: String, attempts: Int = 4, pauseMs: Long = 220L, check: () -> Boolean): Boolean {
        val result = run(command)
        if (result.failed && !check()) {
            lastError = result.text
            return false
        }
        repeat(attempts) {
            if (runCatching(check).getOrDefault(false)) return true
            Thread.sleep(pauseMs)
        }
        return runCatching(check).getOrDefault(false)
    }

    /**
     * Only OEA's own package may appear in a command. Anything else is refused,
     * which keeps a future caller from turning this into a general shell.
     */
    fun sanitizeTarget(packageName: String, own: String): String? {
        if (packageName != own) return null
        if (packageName.any { !(it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') }) return null
        return packageName
    }

    /** Reads at most [MAX_OUTPUT] bytes, so a chatty command cannot fill the heap. */
    private fun readCapped(stream: java.io.InputStream): String = runCatching {
        val out = java.io.ByteArrayOutputStream(16 * 1024)
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            val room = MAX_OUTPUT - total
            if (room <= 0) break
            out.write(buffer, 0, minOf(read, room))
            total += read
        }
        out.toString("UTF-8")
    }.getOrDefault("")

    /** Text used in the diagnostics screen when the channel is unavailable. */
    fun unavailableReason(): String = when {
        !probed -> "Root access has not been checked yet."
        hasSu -> ""
        else -> lastError ?: "No root access on this device, so OEA uses Android's own settings pages instead."
    }
}
