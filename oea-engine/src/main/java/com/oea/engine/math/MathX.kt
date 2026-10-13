package com.oea.engine.math

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Scalar helpers shared by every engine system. All functions are allocation-free. */
object MathX {
    const val PI_F = PI.toFloat()
    const val TAU = (PI * 2.0).toFloat()
    const val HALF_PI = (PI / 2.0).toFloat()
    const val DEG_TO_RAD = (PI / 180.0).toFloat()
    const val RAD_TO_DEG = (180.0 / PI).toFloat()
    const val EPSILON = 1e-6f

    fun clamp(v: Float, min: Float, max: Float): Float = if (v < min) min else if (v > max) max else v
    fun clamp(v: Int, min: Int, max: Int): Int = if (v < min) min else if (v > max) max else v
    fun clamp01(v: Float): Float = clamp(v, 0f, 1f)
    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    fun inverseLerp(a: Float, b: Float, v: Float): Float = if (abs(b - a) < EPSILON) 0f else (v - a) / (b - a)
    fun remap(v: Float, fromMin: Float, fromMax: Float, toMin: Float, toMax: Float): Float =
        lerp(toMin, toMax, inverseLerp(fromMin, fromMax, v))

    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = clamp01((x - edge0) / (edge1 - edge0))
        return t * t * (3f - 2f * t)
    }

    /** Frame-rate independent exponential approach: `current` moves toward `target` with [sharpness] (1/s). */
    fun damp(current: Float, target: Float, sharpness: Float, dt: Float): Float =
        lerp(current, target, 1f - exp(-sharpness * dt))

    /** Moves `current` toward `target` by at most `maxDelta`. */
    fun approach(current: Float, target: Float, maxDelta: Float): Float = when {
        current < target -> minOf(current + maxDelta, target)
        current > target -> maxOf(current - maxDelta, target)
        else -> current
    }

    /** Wraps an angle in radians to (-PI, PI]. */
    fun wrapAngle(a: Float): Float {
        var r = a % TAU
        if (r <= -PI_F) r += TAU
        if (r > PI_F) r -= TAU
        return r
    }

    fun angleDelta(from: Float, to: Float): Float = wrapAngle(to - from)
    fun lerpAngle(a: Float, b: Float, t: Float): Float = a + angleDelta(a, b) * t
    fun dampAngle(current: Float, target: Float, sharpness: Float, dt: Float): Float =
        current + angleDelta(current, target) * (1f - exp(-sharpness * dt))

    fun sign(v: Float): Float = if (v > 0f) 1f else if (v < 0f) -1f else 0f
    fun sq(v: Float): Float = v * v
    fun length(x: Float, y: Float): Float = sqrt(x * x + y * y)
    fun length(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)
    fun nearlyEqual(a: Float, b: Float, eps: Float = 1e-4f): Boolean = abs(a - b) <= eps
    fun isPowerOfTwo(v: Int): Boolean = v > 0 && (v and (v - 1)) == 0
    fun nextPowerOfTwo(v: Int): Int {
        var x = v - 1
        x = x or (x shr 1); x = x or (x shr 2); x = x or (x shr 4); x = x or (x shr 8); x = x or (x shr 16)
        return x + 1
    }

    /** Critically damped spring toward `target`. `velocity` is read from and written to [state] index 0. */
    fun smoothDamp(current: Float, target: Float, state: FloatArray, smoothTime: Float, dt: Float, maxSpeed: Float = Float.MAX_VALUE): Float {
        val st = maxOf(0.0001f, smoothTime)
        val omega = 2f / st
        val x = omega * dt
        val e = 1f / (1f + x + 0.48f * x * x + 0.235f * x * x * x)
        var change = current - target
        val maxChange = maxSpeed * st
        change = clamp(change, -maxChange, maxChange)
        val tgt = current - change
        val temp = (state[0] + omega * change) * dt
        state[0] = (state[0] - omega * temp) * e
        var out = tgt + (change + temp) * e
        if ((target - current > 0f) == (out > target)) {
            out = target
            state[0] = (out - target) / dt
        }
        return out
    }
}

/** Standard easing curves (t in 0..1). */
object Ease {
    fun linear(t: Float) = t
    fun inQuad(t: Float) = t * t
    fun outQuad(t: Float) = 1f - (1f - t) * (1f - t)
    fun inOutQuad(t: Float) = if (t < 0.5f) 2f * t * t else 1f - (-2f * t + 2f).pow(2) / 2f
    fun outCubic(t: Float) = 1f - (1f - t).pow(3)
    fun inOutCubic(t: Float) = if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).pow(3) / 2f
    fun outQuint(t: Float) = 1f - (1f - t).pow(5)
    fun outExpo(t: Float) = if (t >= 1f) 1f else 1f - 2f.pow(-10f * t)
    fun outBack(t: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        return 1f + c3 * (t - 1f).pow(3) + c1 * (t - 1f).pow(2)
    }
    fun outElastic(t: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        val c4 = MathX.TAU / 3f
        return 2f.pow(-10f * t) * sin((t * 10f - 0.75f) * c4) + 1f
    }
    fun outBounce(t: Float): Float {
        val n1 = 7.5625f
        val d1 = 2.75f
        var x = t
        return when {
            x < 1f / d1 -> n1 * x * x
            x < 2f / d1 -> { x -= 1.5f / d1; n1 * x * x + 0.75f }
            x < 2.5f / d1 -> { x -= 2.25f / d1; n1 * x * x + 0.9375f }
            else -> { x -= 2.625f / d1; n1 * x * x + 0.984375f }
        }
    }
    fun inOutSine(t: Float) = -(cos(MathX.PI_F * t) - 1f) / 2f
}

/** ARGB colour helpers; engine colours are packed Ints like Android's. */
object Colors {
    fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b
    fun rgb(r: Int, g: Int, b: Int): Int = argb(255, r, g, b)
    fun alpha(c: Int) = (c ushr 24) and 0xFF
    fun red(c: Int) = (c shr 16) and 0xFF
    fun green(c: Int) = (c shr 8) and 0xFF
    fun blue(c: Int) = c and 0xFF
    fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (MathX.clamp(a, 0, 255) shl 24)
    fun withAlpha(c: Int, a: Float): Int = withAlpha(c, (a * 255f + 0.5f).toInt())
    fun lerp(a: Int, b: Int, t: Float): Int {
        val k = MathX.clamp01(t)
        return argb(
            (alpha(a) + (alpha(b) - alpha(a)) * k).toInt(),
            (red(a) + (red(b) - red(a)) * k).toInt(),
            (green(a) + (green(b) - green(a)) * k).toInt(),
            (blue(a) + (blue(b) - blue(a)) * k).toInt(),
        )
    }
    fun scale(c: Int, f: Float): Int = argb(
        alpha(c),
        MathX.clamp((red(c) * f).toInt(), 0, 255),
        MathX.clamp((green(c) * f).toInt(), 0, 255),
        MathX.clamp((blue(c) * f).toInt(), 0, 255),
    )
    fun luminance(c: Int): Float = (0.2126f * red(c) + 0.7152f * green(c) + 0.0722f * blue(c)) / 255f

    /** Writes the colour as linear-space floats (sRGB decoded) into [out] at [offset]. */
    fun toLinear(c: Int, out: FloatArray, offset: Int = 0) {
        out[offset] = srgbToLinear(red(c) / 255f)
        out[offset + 1] = srgbToLinear(green(c) / 255f)
        out[offset + 2] = srgbToLinear(blue(c) / 255f)
        out[offset + 3] = alpha(c) / 255f
    }

    /** Writes the colour as 0..1 floats without colour-space conversion. */
    fun toSrgb(c: Int, out: FloatArray, offset: Int = 0) {
        out[offset] = red(c) / 255f
        out[offset + 1] = green(c) / 255f
        out[offset + 2] = blue(c) / 255f
        out[offset + 3] = alpha(c) / 255f
    }

    fun srgbToLinear(v: Float): Float = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)

    /** Hue in degrees, saturation and value 0..1. */
    fun hsv(h: Float, s: Float, v: Float, a: Int = 255): Int {
        val hh = ((h % 360f) + 360f) % 360f / 60f
        val i = hh.toInt()
        val f = hh - i
        val p = v * (1f - s)
        val q = v * (1f - s * f)
        val t = v * (1f - s * (1f - f))
        val (r, g, b) = when (i) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
        return argb(a, (r * 255f).toInt(), (g * 255f).toInt(), (b * 255f).toInt())
    }

    const val WHITE = -0x1
    const val BLACK = -0x1000000
    const val TRANSPARENT = 0
}
