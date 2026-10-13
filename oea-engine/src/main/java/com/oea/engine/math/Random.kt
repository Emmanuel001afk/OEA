package com.oea.engine.math

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Deterministic PCG32 generator. The same seed produces the same sequence on
 * every device and Android version, which is what lockstep and seeded
 * multiplayer worlds (the Runner track, the Arena map) rely on.
 */
class Rng(seed: Long = System.nanoTime(), stream: Long = 0x5851F42D4C957F2DL) {
    private var state = 0L
    private val inc = (stream shl 1) or 1L

    init { reseed(seed) }

    fun reseed(seed: Long) {
        state = 0L
        nextInt()
        state += seed
        nextInt()
    }

    /** Uniform 32-bit value. */
    fun nextInt(): Int {
        val old = state
        state = old * 6364136223846793005L + inc
        val xorShifted = (((old ushr 18) xor old) ushr 27).toInt()
        val rot = (old ushr 59).toInt()
        return (xorShifted ushr rot) or (xorShifted shl ((-rot) and 31))
    }

    /** Uniform in [0, bound) without modulo bias. */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        val b = bound.toLong() and 0xFFFFFFFFL
        val threshold = ((1L shl 32) - b) % b
        while (true) {
            val r = nextInt().toLong() and 0xFFFFFFFFL
            if (r >= threshold) return (r % b).toInt()
        }
    }

    /** Uniform in [min, max] inclusive. */
    fun range(min: Int, max: Int): Int = min + nextInt(max - min + 1)
    fun nextLong(): Long = (nextInt().toLong() shl 32) or (nextInt().toLong() and 0xFFFFFFFFL)
    /** Uniform in [0, 1). */
    fun nextFloat(): Float = (nextInt() ushr 8) * (1f / (1 shl 24))
    fun range(min: Float, max: Float): Float = min + (max - min) * nextFloat()
    fun chance(p: Float): Boolean = nextFloat() < p
    fun sign(): Float = if (nextInt() and 1 == 0) -1f else 1f
    fun <T> pick(list: List<T>): T = list[nextInt(list.size)]
    fun <T> pick(array: Array<T>): T = array[nextInt(array.size)]

    /** Index chosen with probability proportional to [weights]. */
    fun weighted(weights: FloatArray): Int {
        var total = 0f
        for (w in weights) total += w
        var r = nextFloat() * total
        for (i in weights.indices) {
            r -= weights[i]
            if (r < 0f) return i
        }
        return weights.lastIndex
    }

    fun <T> shuffle(list: MutableList<T>) {
        for (i in list.lastIndex downTo 1) {
            val j = nextInt(i + 1)
            val t = list[i]; list[i] = list[j]; list[j] = t
        }
    }

    /** Standard normal sample (Box–Muller). */
    fun gaussian(): Float {
        val u1 = maxOf(nextFloat(), 1e-7f)
        val u2 = nextFloat()
        return sqrt(-2f * ln(u1)) * cos(MathX.TAU * u2)
    }

    /** Independent child generator, e.g. one per world chunk, derived deterministically. */
    fun fork(salt: Long): Rng = Rng(nextLong() xor (salt * -0x61c8864680b583ebL))

    companion object {
        /** Stateless hash of integers to [0, 1), handy for per-cell decoration. */
        fun hash01(x: Int, y: Int = 0, seed: Int = 0): Float {
            var h = x * 374761393 + y * 668265263 + seed * 1442695041
            h = (h xor (h ushr 13)) * 1274126177
            h = h xor (h ushr 16)
            return (h ushr 8) * (1f / (1 shl 24))
        }
    }
}

/** Seeded gradient (Perlin) noise and fractal sums for terrain and procedural textures. */
class Noise(seed: Long) {
    private val perm = IntArray(512)

    init {
        val p = IntArray(256) { it }
        val rng = Rng(seed)
        for (i in 255 downTo 1) {
            val j = rng.nextInt(i + 1)
            val t = p[i]; p[i] = p[j]; p[j] = t
        }
        for (i in 0 until 512) perm[i] = p[i and 255]
    }

    private fun fade(t: Float) = t * t * t * (t * (t * 6f - 15f) + 10f)
    private fun grad2(hash: Int, x: Float, y: Float): Float {
        val h = hash and 7
        val u = if (h < 4) x else y
        val v = if (h < 4) y else x
        return (if (h and 1 == 0) u else -u) + (if (h and 2 == 0) 2f * v else -2f * v) * 0.5f
    }
    private fun grad3(hash: Int, x: Float, y: Float, z: Float): Float {
        val h = hash and 15
        val u = if (h < 8) x else y
        val v = if (h < 4) y else if (h == 12 || h == 14) x else z
        return (if (h and 1 == 0) u else -u) + (if (h and 2 == 0) v else -v)
    }

    /** 2D noise in roughly [-1, 1]. */
    fun noise(x: Float, y: Float): Float {
        val xf = floor(x); val yf = floor(y)
        val xi = xf.toInt() and 255; val yi = yf.toInt() and 255
        val dx = x - xf; val dy = y - yf
        val u = fade(dx); val v = fade(dy)
        val aa = perm[perm[xi] + yi]; val ab = perm[perm[xi] + yi + 1]
        val ba = perm[perm[xi + 1] + yi]; val bb = perm[perm[xi + 1] + yi + 1]
        val x1 = MathX.lerp(grad2(aa, dx, dy), grad2(ba, dx - 1f, dy), u)
        val x2 = MathX.lerp(grad2(ab, dx, dy - 1f), grad2(bb, dx - 1f, dy - 1f), u)
        return MathX.lerp(x1, x2, v)
    }

    fun noise(x: Float, y: Float, z: Float): Float {
        val xf = floor(x); val yf = floor(y); val zf = floor(z)
        val xi = xf.toInt() and 255; val yi = yf.toInt() and 255; val zi = zf.toInt() and 255
        val dx = x - xf; val dy = y - yf; val dz = z - zf
        val u = fade(dx); val v = fade(dy); val w = fade(dz)
        val a = perm[xi] + yi; val aa = perm[a] + zi; val ab = perm[a + 1] + zi
        val b = perm[xi + 1] + yi; val ba = perm[b] + zi; val bb = perm[b + 1] + zi
        return MathX.lerp(
            MathX.lerp(
                MathX.lerp(grad3(perm[aa], dx, dy, dz), grad3(perm[ba], dx - 1f, dy, dz), u),
                MathX.lerp(grad3(perm[ab], dx, dy - 1f, dz), grad3(perm[bb], dx - 1f, dy - 1f, dz), u), v),
            MathX.lerp(
                MathX.lerp(grad3(perm[aa + 1], dx, dy, dz - 1f), grad3(perm[ba + 1], dx - 1f, dy, dz - 1f), u),
                MathX.lerp(grad3(perm[ab + 1], dx, dy - 1f, dz - 1f), grad3(perm[bb + 1], dx - 1f, dy - 1f, dz - 1f), u), v),
            w,
        )
    }

    /** Fractal Brownian motion: [octaves] layers of noise. */
    fun fbm(x: Float, y: Float, octaves: Int = 5, lacunarity: Float = 2f, gain: Float = 0.5f): Float {
        var sum = 0f; var amp = 0.5f; var freq = 1f; var norm = 0f
        for (i in 0 until octaves) {
            sum += amp * noise(x * freq, y * freq)
            norm += amp
            amp *= gain
            freq *= lacunarity
        }
        return sum / norm
    }

    /** Ridged multifractal, good for mountain ridges. */
    fun ridged(x: Float, y: Float, octaves: Int = 4): Float {
        var sum = 0f; var amp = 0.5f; var freq = 1f; var norm = 0f
        for (i in 0 until octaves) {
            val n = 1f - kotlin.math.abs(noise(x * freq, y * freq))
            sum += amp * n * n
            norm += amp
            amp *= 0.5f
            freq *= 2f
        }
        return sum / norm
    }
}
