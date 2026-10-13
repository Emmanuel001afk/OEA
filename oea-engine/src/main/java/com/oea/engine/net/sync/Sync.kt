package com.oea.engine.net.sync

import com.oea.engine.math.MathX
import com.oea.engine.math.Vec3

/**
 * Time-stamped states of one remote object, rendered slightly in the past
 * ([delayMs]) and interpolated between the two surrounding snapshots, so
 * remote players move smoothly despite jitter and packet loss. Short gaps
 * are extrapolated.
 *
 * Each state is [stride] floats; components whose bit is set in [angleMask]
 * are treated as angles (shortest-path interpolation).
 */
class SnapshotBuffer(val stride: Int, private val capacity: Int = 32, var delayMs: Long = 100L, private val angleMask: Int = 0) {
    private val times = LongArray(capacity)
    private val data = FloatArray(capacity * stride)
    private var count = 0
    private var head = 0 // index of the oldest

    val isEmpty get() = count == 0
    val latestTime: Long get() = if (count == 0) 0L else times[(head + count - 1) % capacity]

    fun clear() { count = 0; head = 0 }

    /** Adds a snapshot; out-of-order or duplicate ones are ignored. */
    fun push(time: Long, values: FloatArray) {
        if (count > 0 && time <= latestTime) return
        val idx: Int
        if (count < capacity) { idx = (head + count) % capacity; count++ } else { idx = head; head = (head + 1) % capacity }
        times[idx] = time
        System.arraycopy(values, 0, data, idx * stride, stride)
    }

    /** Writes the state at [now] − [delayMs] into [out]; false if nothing was received yet. */
    fun sample(now: Long, out: FloatArray): Boolean {
        if (count == 0) return false
        val t = now - delayMs
        val last = (head + count - 1) % capacity
        if (count == 1 || t >= times[last]) {
            // Extrapolate up to 250 ms from the last two snapshots.
            if (count >= 2) {
                val prev = (head + count - 2) % capacity
                val span = (times[last] - times[prev]).coerceAtLeast(1L)
                val ahead = (t - times[last]).coerceIn(0L, 250L)
                val k = ahead.toFloat() / span
                blend(prev, last, 1f + k, out)
            } else {
                System.arraycopy(data, last * stride, out, 0, stride)
            }
            return true
        }
        if (t <= times[head]) { System.arraycopy(data, head * stride, out, 0, stride); return true }
        for (i in 0 until count - 1) {
            val a = (head + i) % capacity
            val b = (head + i + 1) % capacity
            if (t >= times[a] && t <= times[b]) {
                val k = (t - times[a]).toFloat() / (times[b] - times[a]).coerceAtLeast(1L)
                blend(a, b, k, out)
                return true
            }
        }
        System.arraycopy(data, last * stride, out, 0, stride)
        return true
    }

    private fun blend(a: Int, b: Int, k: Float, out: FloatArray) {
        for (c in 0 until stride) {
            val va = data[a * stride + c]
            val vb = data[b * stride + c]
            out[c] = if ((angleMask shr c) and 1 == 1) va + MathX.angleDelta(va, vb) * k else va + (vb - va) * k
        }
    }
}

/**
 * Client-side prediction support: inputs are kept (by sequence number) until
 * the authority acknowledges them; after a correction the unacknowledged ones
 * are replayed on top of the authoritative state.
 */
class InputHistory<T>(private val capacity: Int = 256) {
    private val seqs = IntArray(capacity)
    private val items = arrayOfNulls<Any>(capacity)
    private var count = 0
    private var head = 0

    fun push(seq: Int, input: T) {
        val idx: Int
        if (count < capacity) { idx = (head + count) % capacity; count++ } else { idx = head; head = (head + 1) % capacity }
        seqs[idx] = seq
        items[idx] = input
    }

    /** Forgets everything up to and including [seq]. */
    fun acknowledge(seq: Int) {
        while (count > 0 && seqDiff(seqs[head], seq) <= 0) { items[head] = null; head = (head + 1) % capacity; count-- }
    }

    @Suppress("UNCHECKED_CAST")
    fun forEachPending(block: (Int, T) -> Unit) {
        for (i in 0 until count) { val idx = (head + i) % capacity; block(seqs[idx], items[idx] as T) }
    }

    val size get() = count
    fun clear() { while (count > 0) { items[head] = null; head = (head + 1) % capacity; count-- } }

    companion object {
        /** Wrap-safe comparison of 16-bit sequence numbers. */
        fun seqDiff(a: Int, b: Int): Int {
            val d = (a - b) and 0xFFFF
            return if (d > 0x7FFF) d - 0x10000 else d
        }
    }
}

/**
 * Authority-side lag compensation: remembers where an object was, so a shot
 * fired by a client [rttMs]/2 + interpolation delay ago is tested against
 * the position that client actually saw.
 */
class PositionHistory(private val capacity: Int = 64) {
    private val times = LongArray(capacity)
    private val xs = FloatArray(capacity)
    private val ys = FloatArray(capacity)
    private val zs = FloatArray(capacity)
    private var count = 0
    private var head = 0

    fun record(time: Long, x: Float, y: Float, z: Float) {
        val idx: Int
        if (count < capacity) { idx = (head + count) % capacity; count++ } else { idx = head; head = (head + 1) % capacity }
        times[idx] = time; xs[idx] = x; ys[idx] = y; zs[idx] = z
    }

    /** Position at [time] (interpolated, clamped to the stored range). */
    fun sample(time: Long, out: Vec3): Boolean {
        if (count == 0) return false
        val last = (head + count - 1) % capacity
        if (time >= times[last]) { out.set(xs[last], ys[last], zs[last]); return true }
        if (time <= times[head]) { out.set(xs[head], ys[head], zs[head]); return true }
        for (i in count - 2 downTo 0) {
            val a = (head + i) % capacity
            val b = (head + i + 1) % capacity
            if (time >= times[a]) {
                val k = (time - times[a]).toFloat() / (times[b] - times[a]).coerceAtLeast(1L)
                out.set(MathX.lerp(xs[a], xs[b], k), MathX.lerp(ys[a], ys[b], k), MathX.lerp(zs[a], zs[b], k))
                return true
            }
        }
        out.set(xs[last], ys[last], zs[last])
        return true
    }

    fun clear() { count = 0; head = 0 }
}
