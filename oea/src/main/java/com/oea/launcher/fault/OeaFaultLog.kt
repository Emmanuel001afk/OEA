package com.oea.launcher.fault

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * One contained fault, kept in a small ring file so the cause of a force-close
 * can still be read after Android restarts the process.
 *
 * Records are written as tab-separated text: no JSON library, readable with any
 * editor, and one broken line never hides the rest (parsing skips what it cannot
 * read instead of throwing).
 */
data class OeaFaultRecord(
    val at: Long,
    val process: String,
    val thread: String,
    val kind: ThreadKind,
    val subsystem: String,
    val errorClass: String,
    val message: String,
    val frames: List<String>,
    val action: FaultAction,
    val reason: String,
    val repeats: Int
) {
    /** One-line summary for a list row. */
    fun title(): String = "${subsystemLabel()} • $errorClass"

    fun subsystemLabel(): String = subsystem.substringBefore(':').replace('-', ' ').replaceFirstChar { it.uppercase() }

    /** Long-form text for the detail sheet and for sharing a report. */
    fun body(): String = buildString {
        append(subsystem)
        append(" on ").append(thread).append(" (").append(kind.name.lowercase()).append(")\n")
        if (message.isNotBlank()) append(message).append('\n')
        frames.forEach { append("  ").append(it).append('\n') }
        append(action.name.lowercase().replace('_', ' ')).append(": ").append(reason)
        if (repeats > 1) append(" (x").append(repeats).append(')')
    }

    fun toLine(): String = listOf(
        at.toString(),
        process,
        clean(thread),
        kind.name,
        clean(subsystem),
        clean(errorClass),
        clean(message),
        clean(frames.joinToString(" <- ")),
        action.name,
        clean(reason),
        repeats.toString()
).joinToString("\t")

    companion object {
        private const val FIELDS = 11
        private const val MAX_MESSAGE = 240
        private const val MAX_FRAMES = 5

        fun of(
            kind: ThreadKind,
            thread: Thread,
            subsystem: String,
            error: Throwable,
            action: FaultAction,
            reason: String,
            repeats: Int,
            process: String
): OeaFaultRecord = OeaFaultRecord(
            at = System.currentTimeMillis(),
            process = process,
            thread = thread.name,
            kind = kind,
            subsystem = subsystem,
            errorClass = error.javaClass.simpleName.ifBlank { error.javaClass.name },
            message = error.message.orEmpty().take(MAX_MESSAGE),
            frames = trace(error),
            action = action,
            reason = reason,
            repeats = repeats
)

        /** The caller's own frames, which is where the fault actually happened. */
        fun callerFrames(skip: Int = 3): List<String> {
            val all = Thread.currentThread().stackTrace
            val out = ArrayList<String>(MAX_FRAMES)
            var i = skip
            while (i < all.size && out.size < MAX_FRAMES) {
                val e = all[i]
                if (!e.className.startsWith("com.oea.launcher.fault.")) out += "${e.methodName}(${e.lineNumber})"
                i++
            }
            return out
        }

        private fun trace(error: Throwable): List<String> {
            val stack = error.stackTrace
            val out = ArrayList<String>(MAX_FRAMES)
            var i = 0
            while (i < stack.size && out.size < MAX_FRAMES) {
                val e = stack[i]
                out += "${e.methodName}(${e.lineNumber})"
                i++
            }
            return out
        }

        private fun clean(value: String): String =
            value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim().take(MAX_MESSAGE)

        fun parse(line: String): OeaFaultRecord? {
            val parts = line.split('\t')
            if (parts.size < FIELDS) return null
            return runCatching {
                OeaFaultRecord(
                    at = parts[0].toLong(),
                    process = parts[1],
                    thread = parts[2],
                    kind = ThreadKind.valueOf(parts[3]),
                    subsystem = parts[4],
                    errorClass = parts[5],
                    message = parts[6],
                    frames = if (parts[7].isBlank()) emptyList() else parts[7].split(" <- "),
                    action = FaultAction.valueOf(parts[8]),
                    reason = parts[9],
                    repeats = parts[10].toIntOrNull() ?: 1
)
            }.getOrNull()
        }
    }
}

/**
 * In-memory view of recent faults plus the persisted file that survives a
 * process restart. Writes are best effort and never throw: this is called from
 * inside fault handling, sometimes from a thread that is about to die.
 */
object OeaFaultLog {
    private const val TAG = "OeaFault"
    private const val FILE = "diagnostics/faults.tsv"
    private const val MAX_LINES = 240
    private const val MAX_BYTES = 160 * 1024

    private val lock = Any()
    private val memory = ArrayDeque<OeaFaultRecord>()
    private var app: Context? = null

    /** When false, faults stay in memory only (no file writes). */
    @Volatile
    var persistence = true

    fun init(context: Context) {
        synchronized(lock) {
            app = context.applicationContext
            val file = file(context)
            if (!file.exists()) return
            val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
            lines.mapNotNull { OeaFaultRecord.parse(it.trim()) }.forEach { memory.addLast(it) }
            while (memory.size > MAX_LINES) memory.removeFirst()
        }
    }

    /** Records are also mirrored to Logcat so a device connected to a PC shows them live. */
    fun record(entry: OeaFaultRecord) {
        Log.println(if (entry.action == FaultAction.ESCALATE) Log.ASSERT else Log.ERROR, TAG, "${entry.title()} — ${entry.reason}")
        val context = synchronized(lock) {
            memory.addLast(entry)
            while (memory.size > MAX_LINES) memory.removeFirst()
            app
        }
        if (persistence) context?.let { append(it, entry.toLine()) }
    }

    /** Newest first, for the Stability screen. */
    fun history(): List<OeaFaultRecord> = synchronized(lock) { memory.toList().asReversed() }

    fun count(): Int = synchronized(lock) { memory.size }

    fun clear(context: Context) {
        synchronized(lock) { memory.clear() }
        runCatching { file(context).delete() }
    }

    /** Timestamp of the newest fault with [action], or 0 when there is none. */
    fun latest(action: FaultAction): Long = synchronized(lock) {
        memory.lastOrNull { it.action == action }?.at ?: 0L
    }

    fun text(): String = synchronized(lock) { memory.toList().asReversed().joinToString("\n\n") { it.body() } }

    private fun file(context: Context): File {
        val dir = File(context.filesDir, "diagnostics")
        if (!dir.exists()) runCatching { dir.mkdirs() }
        return File(dir, "faults.tsv")
    }

    private fun append(context: Context, line: String) {
        runCatching {
            val target = file(context)
            if (target.length() > MAX_BYTES) {
                // Keep the newer half of the log rather than growing forever.
                val all = target.readLines()
                target.writeText(all.drop(all.size / 2).joinToString("\n") + "\n")
            }
            RandomAccessFile(target, "rw").use { raf ->
                raf.seek(raf.length())
                raf.write((line + "\n").toByteArray())
                runCatching { raf.fd.sync() }
            }
        }
    }
}
