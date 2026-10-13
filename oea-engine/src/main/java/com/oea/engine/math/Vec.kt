package com.oea.engine.math

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mutable vectors. Operators (`a + b`) allocate and are meant for setup code;
 * hot paths use the in-place methods (`set`, `add`, `scl`, `mulAdd` ...), which
 * return `this` so calls can be chained without garbage.
 */
class Vec2(@JvmField var x: Float = 0f, @JvmField var y: Float = 0f) {
    constructor(v: Vec2) : this(v.x, v.y)

    fun set(x: Float, y: Float): Vec2 { this.x = x; this.y = y; return this }
    fun set(v: Vec2): Vec2 = set(v.x, v.y)
    fun add(x: Float, y: Float): Vec2 { this.x += x; this.y += y; return this }
    fun add(v: Vec2): Vec2 = add(v.x, v.y)
    fun sub(v: Vec2): Vec2 { x -= v.x; y -= v.y; return this }
    fun scl(s: Float): Vec2 { x *= s; y *= s; return this }
    fun mulAdd(v: Vec2, s: Float): Vec2 { x += v.x * s; y += v.y * s; return this }
    fun len(): Float = sqrt(x * x + y * y)
    fun len2(): Float = x * x + y * y
    fun dot(v: Vec2): Float = x * v.x + y * v.y
    fun cross(v: Vec2): Float = x * v.y - y * v.x
    fun dst(v: Vec2): Float = MathX.length(x - v.x, y - v.y)
    fun angle(): Float = atan2(y, x)
    fun nor(): Vec2 {
        val l = len()
        if (l > MathX.EPSILON) { x /= l; y /= l }
        return this
    }
    fun limit(max: Float): Vec2 {
        val l2 = len2()
        if (l2 > max * max) scl(max / sqrt(l2))
        return this
    }
    fun rotate(rad: Float): Vec2 {
        val c = cos(rad); val s = sin(rad)
        return set(x * c - y * s, x * s + y * c)
    }
    fun lerp(target: Vec2, t: Float): Vec2 = set(x + (target.x - x) * t, y + (target.y - y) * t)
    fun isZero(eps: Float = MathX.EPSILON): Boolean = abs(x) <= eps && abs(y) <= eps
    fun copy(): Vec2 = Vec2(x, y)

    operator fun plus(v: Vec2) = Vec2(x + v.x, y + v.y)
    operator fun minus(v: Vec2) = Vec2(x - v.x, y - v.y)
    operator fun times(s: Float) = Vec2(x * s, y * s)
    operator fun unaryMinus() = Vec2(-x, -y)
    override fun toString() = "($x, $y)"
    override fun equals(other: Any?) = other is Vec2 && other.x == x && other.y == y
    override fun hashCode() = 31 * x.hashCode() + y.hashCode()

    companion object {
        val ZERO get() = Vec2()
    }
}

class Vec3(@JvmField var x: Float = 0f, @JvmField var y: Float = 0f, @JvmField var z: Float = 0f) {
    constructor(v: Vec3) : this(v.x, v.y, v.z)

    fun set(x: Float, y: Float, z: Float): Vec3 { this.x = x; this.y = y; this.z = z; return this }
    fun set(v: Vec3): Vec3 = set(v.x, v.y, v.z)
    fun setZero(): Vec3 = set(0f, 0f, 0f)
    fun add(x: Float, y: Float, z: Float): Vec3 { this.x += x; this.y += y; this.z += z; return this }
    fun add(v: Vec3): Vec3 = add(v.x, v.y, v.z)
    fun sub(x: Float, y: Float, z: Float): Vec3 { this.x -= x; this.y -= y; this.z -= z; return this }
    fun sub(v: Vec3): Vec3 = sub(v.x, v.y, v.z)
    fun scl(s: Float): Vec3 { x *= s; y *= s; z *= s; return this }
    fun scl(v: Vec3): Vec3 { x *= v.x; y *= v.y; z *= v.z; return this }
    fun mulAdd(v: Vec3, s: Float): Vec3 { x += v.x * s; y += v.y * s; z += v.z * s; return this }
    fun len(): Float = sqrt(x * x + y * y + z * z)
    fun len2(): Float = x * x + y * y + z * z
    fun dot(v: Vec3): Float = x * v.x + y * v.y + z * v.z
    fun dot(x: Float, y: Float, z: Float): Float = this.x * x + this.y * y + this.z * z
    fun dst(v: Vec3): Float = MathX.length(x - v.x, y - v.y, z - v.z)
    fun dst2(v: Vec3): Float { val dx = x - v.x; val dy = y - v.y; val dz = z - v.z; return dx * dx + dy * dy + dz * dz }
    fun dstXZ(v: Vec3): Float = MathX.length(x - v.x, z - v.z)
    fun crs(v: Vec3): Vec3 = set(y * v.z - z * v.y, z * v.x - x * v.z, x * v.y - y * v.x)
    fun nor(): Vec3 {
        val l = len()
        if (l > MathX.EPSILON) { x /= l; y /= l; z /= l }
        return this
    }
    fun limit(max: Float): Vec3 {
        val l2 = len2()
        if (l2 > max * max) scl(max / sqrt(l2))
        return this
    }
    fun lerp(t: Vec3, a: Float): Vec3 = set(x + (t.x - x) * a, y + (t.y - y) * a, z + (t.z - z) * a)
    fun damp(target: Vec3, sharpness: Float, dt: Float): Vec3 {
        x = MathX.damp(x, target.x, sharpness, dt); y = MathX.damp(y, target.y, sharpness, dt); z = MathX.damp(z, target.z, sharpness, dt)
        return this
    }
    /** Rotates around the world Y axis by [rad]. */
    fun rotateY(rad: Float): Vec3 {
        val c = cos(rad); val s = sin(rad)
        return set(x * c + z * s, y, -x * s + z * c)
    }
    fun isZero(eps: Float = MathX.EPSILON): Boolean = abs(x) <= eps && abs(y) <= eps && abs(z) <= eps
    fun copy(): Vec3 = Vec3(x, y, z)
    fun toArray(out: FloatArray, offset: Int = 0) { out[offset] = x; out[offset + 1] = y; out[offset + 2] = z }

    operator fun plus(v: Vec3) = Vec3(x + v.x, y + v.y, z + v.z)
    operator fun minus(v: Vec3) = Vec3(x - v.x, y - v.y, z - v.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    infix fun cross(v: Vec3) = Vec3(y * v.z - z * v.y, z * v.x - x * v.z, x * v.y - y * v.x)
    override fun toString() = "($x, $y, $z)"
    override fun equals(other: Any?) = other is Vec3 && other.x == x && other.y == y && other.z == z
    override fun hashCode() = (31 * x.hashCode() + y.hashCode()) * 31 + z.hashCode()

    companion object {
        val UP get() = Vec3(0f, 1f, 0f)
        val FORWARD get() = Vec3(0f, 0f, 1f)
        val RIGHT get() = Vec3(1f, 0f, 0f)
        fun fromYawPitch(yaw: Float, pitch: Float, out: Vec3 = Vec3()): Vec3 {
            val cp = cos(pitch)
            return out.set(sin(yaw) * cp, sin(pitch), cos(yaw) * cp)
        }
    }
}

class Vec4(@JvmField var x: Float = 0f, @JvmField var y: Float = 0f, @JvmField var z: Float = 0f, @JvmField var w: Float = 0f) {
    fun set(x: Float, y: Float, z: Float, w: Float): Vec4 { this.x = x; this.y = y; this.z = z; this.w = w; return this }
    fun set(v: Vec4): Vec4 = set(v.x, v.y, v.z, v.w)
    fun dot(v: Vec4): Float = x * v.x + y * v.y + z * v.z + w * v.w
    fun scl(s: Float): Vec4 { x *= s; y *= s; z *= s; w *= s; return this }
    fun copy(): Vec4 = Vec4(x, y, z, w)
    override fun toString() = "($x, $y, $z, $w)"

    companion object {
        /** Linear-space RGBA from an ARGB Int. */
        fun color(argb: Int, out: Vec4 = Vec4()): Vec4 {
            val tmp = FloatArray(4)
            Colors.toLinear(argb, tmp)
            return out.set(tmp[0], tmp[1], tmp[2], tmp[3])
        }
    }
}
