package com.oea.engine.core

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Timing information for the current frame. */
class GameTime internal constructor() {
    /** Seconds since the game started (unscaled, excludes pauses). */
    var elapsed = 0.0
        internal set
    /** Last frame's duration in seconds (clamped, scaled by [timeScale]). */
    var delta = 0f
        internal set
    /** Fixed simulation step in seconds. */
    var fixedDelta = 1f / 60f
        internal set
    /** 0..1 position between the last two fixed steps, for interpolated rendering. */
    var alpha = 0f
        internal set
    var frame = 0L
        internal set
    var fixedTick = 0L
        internal set
    /** Slow motion / fast forward; 0 freezes the simulation. */
    var timeScale = 1f
}

/** Background work with results delivered back on the game thread. */
class Jobs internal constructor() {
    private val counter = AtomicInteger()
    private val pool: ExecutorService = Executors.newFixedThreadPool(
        maxOf(2, Runtime.getRuntime().availableProcessors() - 2),
        OeaGuard.factory("oea-job", "jobs", Process.THREAD_PRIORITY_BACKGROUND + Process.THREAD_PRIORITY_MORE_FAVORABLE),
    )
    private val completions = ConcurrentLinkedQueue<() -> Unit>()

    /** A running background job; [cancelled] is a cooperative flag the work can poll. */
    class Job internal constructor() {
        internal var future: Future<*>? = null
        private val cancelFlag = AtomicBoolean(false)
        val cancelled get() = cancelFlag.get()
        @Volatile var done = false
            internal set
        fun cancel() {
            cancelFlag.set(true)
            future?.cancel(false)
        }
    }

    /** Runs [work] on a worker; [onDone] / [onError] run on the game thread unless cancelled. */
    fun <T> run(work: (Job) -> T, onError: (Throwable) -> Unit = { Log.w(TAG, "Job failed", it) }, onDone: (T) -> Unit): Job {
        val job = Job()
        job.future = pool.submit {
            try {
                val result = work(job)
                if (!job.cancelled) completions += { job.done = true; if (!job.cancelled) onDone(result) }
            } catch (t: Throwable) {
                if (!job.cancelled) completions += { job.done = true; onError(t) }
            }
        }
        return job
    }

    internal fun post(action: () -> Unit) { completions += action }

    internal fun drain() {
        var guard = 0
        while (guard++ < 256) {
            val next = completions.poll() ?: break
            try { next() } catch (t: Throwable) { Log.e(TAG, "Game-thread task failed", t) }
        }
    }

    internal fun shutdown() { pool.shutdownNow() }

    private companion object { const val TAG = "OeaJobs" }
}

/** Per-game save data (best scores, unlocks, preferences) and a private folder. */
class GameStorage internal constructor(context: Context, val gameId: String) {
    private val prefs = context.getSharedPreferences("oea_game_$gameId", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "games/$gameId")

    fun getInt(key: String, def: Int = 0) = prefs.getInt(key, def)
    fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    fun getLong(key: String, def: Long = 0L) = prefs.getLong(key, def)
    fun putLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
    fun getFloat(key: String, def: Float = 0f) = prefs.getFloat(key, def)
    fun putFloat(key: String, value: Float) = prefs.edit().putFloat(key, value).apply()
    fun getString(key: String, def: String = "") = prefs.getString(key, def) ?: def
    fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    fun getBoolean(key: String, def: Boolean = false) = prefs.getBoolean(key, def)
    fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()

    /** Stores [value] if it beats the saved best for [key]; returns true on a new record. */
    fun submitBest(key: String, value: Long, higherIsBetter: Boolean = true): Boolean {
        val has = prefs.contains("best_$key")
        val old = prefs.getLong("best_$key", 0L)
        val better = !has || (if (higherIsBetter) value > old else value < old)
        if (better) prefs.edit().putLong("best_$key", value).apply()
        return better
    }

    fun best(key: String): Long = prefs.getLong("best_$key", 0L)

    /** A file inside this game's private folder. */
    fun file(name: String): File {
        if (!dir.exists()) dir.mkdirs()
        return File(dir, name)
    }
}
