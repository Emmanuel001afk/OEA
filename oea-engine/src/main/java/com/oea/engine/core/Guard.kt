package com.oea.engine.core

import android.os.Handler
import android.os.Process
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Fault containment for everything the engine runs away from the UI thread.
 *
 * The engine is a library inside the host app's process. A listener that
 * throws on a socket, audio, decode or simulation thread would otherwise
 * reach the thread's uncaught-exception handler and Android would kill the
 * whole process, taking the host's UI (for OEA: Home) down with it. Every
 * engine thread, listener dispatch and queued task therefore runs through
 * [OeaGuard], which:
 *
 * - converts a thrown [Throwable] (including [Error]) into a reported fault;
 * - counts faults per source so the host can show a health summary;
 * - hands the fault to the host through [sink] (the host decides what to
 *   close, log or recover);
 * - keeps a per-thread [Thread.UncaughtExceptionHandler] as a last-resort net
 *   for code paths that call into the engine and throw on a thread it owns.
 *
 * A fault here is a *stopped subsystem*, never a crashed host.
 */
object OeaGuard {
    private const val TAG = "OeaGuard"

    /** Receives every fault the engine contained. Installed by the host app. */
    fun interface Sink {
        /**
         * @param source stable subsystem tag, e.g. `lan-server` or `game-runner`.
         * @param fatal true when the subsystem itself cannot continue (its loop
         *   ended); the host should close or restart only that feature.
         */
        fun onFault(source: String, error: Throwable, fatal: Boolean)
    }

    @Volatile
    var sink: Sink? = null

    private val counts = ConcurrentHashMap<String, AtomicInteger>()
    private val lastSeen = ConcurrentHashMap<String, AtomicLong>()

    /** Number of contained faults per source since the process started. */
    fun faultCounts(): Map<String, Int> = counts.mapValues { it.value.get() }

    /** Uptime millis of the last fault per source, for a health summary. */
    fun lastFaultAt(): Map<String, Long> = lastSeen.mapValues { it.value.get() }

    fun resetCounts() {
        counts.clear()
        lastSeen.clear()
    }

    /**
     * Reports a fault that was already caught by the caller. Safe to call from
     * any thread, including a dying one: no allocation beyond the counters and
     * the host sink (which is itself guarded).
     */
    fun report(source: String, error: Throwable, fatal: Boolean = false) {
        counts.computeIfAbsent(source) { AtomicInteger() }.incrementAndGet()
        lastSeen.computeIfAbsent(source) { AtomicLong() }.set(System.currentTimeMillis())
        val handler = sink
        if (handler == null) {
            Log.e(TAG, "[$source] contained a fault", error)
            return
        }
        try {
            handler.onFault(source, error, fatal)
        } catch (inner: Throwable) {
            Log.e(TAG, "[$source] fault sink failed; fault was " + error.javaClass.name, inner)
        }
    }

    /** Runs [block]; a fault is contained and reported. Returns true when it ran cleanly. */
    inline fun run(source: String, block: () -> Unit): Boolean {
        try {
            block()
            return true
        } catch (t: Throwable) {
            OeaGuard.report(source, t)
            return false
        }
    }

    /** Runs [block] and returns its value, or [fallback] when it throws. */
    inline fun <T> call(source: String, fallback: T, block: () -> T): T =
        try {
            block()
        } catch (t: Throwable) {
            OeaGuard.report(source, t)
            fallback
        }

    /** Wraps [action] so the work inside it can never take the host process down. */
    fun wrap(source: String, action: () -> Unit): Runnable = Runnable { run(source, action) }

    /** Posts [action] to [handler] through [wrap], so a queued task cannot kill the process. */
    fun post(source: String, handler: Handler, delayMs: Long = 0L, action: () -> Unit): Boolean =
        handler.postDelayed(wrap(source, action), delayMs)

    /** A daemon thread that reports its own death instead of crashing the process. */
    fun thread(name: String, source: String, priority: Int = Process.THREAD_PRIORITY_BACKGROUND, body: () -> Unit): Thread =
        Thread({
            Process.setThreadPriority(priority)
            run(source, body)
        }, name).apply {
            isDaemon = true
            setUncaughtExceptionHandler { t, e -> OeaGuard.report(source + ":" + t.name, e, fatal = true) }
        }

    /** Starts a guarded daemon thread. */
    fun start(name: String, source: String, priority: Int = Process.THREAD_PRIORITY_BACKGROUND, body: () -> Unit): Thread =
        thread(name, source, priority, body).apply { start() }

    /** A guarded thread factory; every task this pool runs is contained. */
    fun factory(prefix: String, source: String, priority: Int = Process.THREAD_PRIORITY_BACKGROUND): ThreadFactory {
        val id = AtomicInteger()
        return ThreadFactory { r ->
            Thread({
                Process.setThreadPriority(priority)
                run(source, r)
            }, "$prefix-${id.incrementAndGet()}").apply {
                isDaemon = true
                setUncaughtExceptionHandler { t, e -> OeaGuard.report("$source:${t.name}", e, fatal = true) }
            }
        }
    }

    fun pool(source: String, threads: Int, prefix: String = "oea-$source"): ExecutorService =
        if (threads <= 1) Executors.newSingleThreadExecutor(factory(prefix, source))
        else Executors.newFixedThreadPool(threads.coerceIn(1, 16), factory(prefix, source))

    fun scheduled(source: String, prefix: String = "oea-$source"): ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(factory(prefix, source))

    /**
     * A serial worker queue with a bounded backlog. Tasks that arrive while the
     * queue is full are dropped (and reported) instead of growing memory without
     * limit, which keeps a stuck subsystem from exhausting the heap of the host.
     */
    class Queue(private val source: String, name: String, private val maxPending: Int = 256) {
        private val backlog = LinkedBlockingQueue<Runnable>(maxPending)
        private val worker = Thread({ pump() }, name).apply {
            isDaemon = true
            setUncaughtExceptionHandler { t, e -> OeaGuard.report("$source:pump", e, fatal = true) }
        }
        @Volatile private var alive = true

        /** True when the task ran; false when it was rejected (queue full) or the queue stopped. */
        fun submit(action: () -> Unit): Boolean {
            if (!alive) return false
            val added = try {
                backlog.offer(Runnable { OeaGuard.run(source, action) })
            } catch (t: Throwable) {
                OeaGuard.report("$source:submit", t)
                false
            }
            if (!added) OeaGuard.report(source, IllegalStateException("Task queue is full ($maxPending); dropped work"))
            return added
        }

        private fun pump() {
            while (alive) {
                val next = try {
                    backlog.take()
                } catch (e: InterruptedException) {
                    return
                } catch (t: Throwable) {
                    OeaGuard.report("$source:pump", t, fatal = true)
                    return
                }
                try {
                    next.run()
                } catch (t: Throwable) {
                    OeaGuard.report(source, t)
                }
            }
        }

        fun stop() {
            alive = false
            backlog.clear()
            runCatching { worker.interrupt() }
        }

        init {
            worker.start()
        }
    }

    /** Shuts a pool down without letting a hung task block the caller. */
    fun close(executor: ExecutorService?, quiet: Boolean = true) {
        executor ?: return
        try {
            executor.shutdown()
            if (!executor.awaitTermination(120, TimeUnit.MILLISECONDS)) executor.shutdownNow()
        } catch (e: InterruptedException) {
            executor.shutdownNow()
            if (!quiet) Log.d(TAG, "interrupted while stopping a pool")
        } catch (t: Throwable) {
            if (!quiet) OeaGuard.report("executor-close", t)
        }
    }
}
