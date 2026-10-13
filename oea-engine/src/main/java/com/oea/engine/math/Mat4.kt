package com.oea.engine.math

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Unit quaternion rotation (x, y, z, w). */
class Quat(@JvmField var x: Float = 0f, @JvmField var y: Float = 0f, @JvmField var z: Float = 0f, @JvmField var w: Float = 1f) {
    fun set(x: Float, y: Float, z: Float, w: Float): Quat { this.x = x; this.y = y; this.z = z; this.w = w; return this }
    fun set(q: Quat): Quat = set(q.x, q.y, q.z, q.w)
    fun idt(): Quat = set(0f, 0f, 0f, 1f)

    fun setAxisAngle(ax: Float, ay: Float, az: Float, rad: Float): Quat {
        val l = MathX.length(ax, ay, az)
        if (l < MathX.EPSILON) return idt()
        val s = sin(rad / 2f) / l
        return set(ax * s, ay * s, az * s, cos(rad / 2f))
    }

    /** Same convention as [Mat4.setTRS]: yaw around +Y (0 faces +Z), positive pitch looks up, roll around the forward axis. */
    fun setEuler(yaw: Float, pitch: Float, roll: Float): Quat {
        val qy = Quat().setAxisAngle(0f, 1f, 0f, yaw)
        val qx = Quat().setAxisAngle(1f, 0f, 0f, -pitch)
        val qz = Quat().setAxisAngle(0f, 0f, 1f, roll)
        return set(qy).mul(qx).mul(qz)
    }

    /** this = this * q */
    fun mul(q: Quat): Quat {
        val nx = w * q.x + x * q.w + y * q.z - z * q.y
        val ny = w * q.y + y * q.w + z * q.x - x * q.z
        val nz = w * q.z + z * q.w + x * q.y - y * q.x
        val nw = w * q.w - x * q.x - y * q.y - z * q.z
        return set(nx, ny, nz, nw)
    }

    fun nor(): Quat {
        val l = sqrt(x * x + y * y + z * z + w * w)
        if (l > MathX.EPSILON) { x /= l; y /= l; z /= l; w /= l }
        return this
    }

    fun conjugate(): Quat = set(-x, -y, -z, w)

    /** Rotates [v] in place. */
    fun transform(v: Vec3): Vec3 {
        // v' = v + 2w(q×v) + 2q×(q×v)
        val tx = 2f * (y * v.z - z * v.y)
        val ty = 2f * (z * v.x - x * v.z)
        val tz = 2f * (x * v.y - y * v.x)
        return v.set(
            v.x + w * tx + (y * tz - z * ty),
            v.y + w * ty + (z * tx - x * tz),
            v.z + w * tz + (x * ty - y * tx),
        )
    }

    fun slerp(target: Quat, t: Float): Quat {
        var cosHalf = x * target.x + y * target.y + z * target.z + w * target.w
        var tx = target.x; var ty = target.y; var tz = target.z; var tw = target.w
        if (cosHalf < 0f) { cosHalf = -cosHalf; tx = -tx; ty = -ty; tz = -tz; tw = -tw }
        if (cosHalf > 0.9995f) {
            return set(x + (tx - x) * t, y + (ty - y) * t, z + (tz - z) * t, w + (tw - w) * t).nor()
        }
        val half = acos(cosHalf)
        val sinHalf = sqrt(1f - cosHalf * cosHalf)
        val a = sin((1f - t) * half) / sinHalf
        val b = sin(t * half) / sinHalf
        return set(x * a + tx * b, y * a + ty * b, z * a + tz * b, w * a + tw * b)
    }

    fun copy() = Quat(x, y, z, w)
    override fun toString() = "Quat($x, $y, $z, $w)"
}

/**
 * Column-major 4x4 matrix, laid out exactly as OpenGL expects, so [m] can be
 * uploaded with `glUniformMatrix4fv(..., false, m, 0)`. Element (row r,
 * column c) lives at `m[c * 4 + r]`.
 */
class Mat4(@JvmField val m: FloatArray = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }) {

    fun idt(): Mat4 {
        m.fill(0f)
        m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f
        return this
    }

    fun set(o: Mat4): Mat4 { System.arraycopy(o.m, 0, m, 0, 16); return this }
    fun set(values: FloatArray, offset: Int = 0): Mat4 { System.arraycopy(values, offset, m, 0, 16); return this }

    /** this = a * b (a and b may alias this). */
    fun mul(a: Mat4, b: Mat4): Mat4 {
        mul(a.m, 0, b.m, 0, tmp)
        System.arraycopy(tmp, 0, m, 0, 16)
        return this
    }

    /** this = this * b */
    fun mul(b: Mat4): Mat4 = mul(this, b)

    /** Sets a translation / rotation (yaw, pitch, roll in radians) / scale transform. */
    fun setTRS(px: Float, py: Float, pz: Float, yaw: Float = 0f, pitch: Float = 0f, roll: Float = 0f, sx: Float = 1f, sy: Float = 1f, sz: Float = 1f): Mat4 {
        val cy = cos(yaw); val sy0 = sin(yaw)
        val cx = cos(pitch); val sx0 = sin(pitch)
        val cz = cos(roll); val sz0 = sin(roll)
        val r00 = cy * cz - sy0 * sx0 * sz0
        val r01 = -cy * sz0 - sy0 * sx0 * cz
        val r02 = sy0 * cx
        val r10 = cx * sz0
        val r11 = cx * cz
        val r12 = sx0
        val r20 = -sy0 * cz - cy * sx0 * sz0
        val r21 = sy0 * sz0 - cy * sx0 * cz
        val r22 = cy * cx
        m[0] = r00 * sx; m[1] = r10 * sx; m[2] = r20 * sx; m[3] = 0f
        m[4] = r01 * sy; m[5] = r11 * sy; m[6] = r21 * sy; m[7] = 0f
        m[8] = r02 * sz; m[9] = r12 * sz; m[10] = r22 * sz; m[11] = 0f
        m[12] = px; m[13] = py; m[14] = pz; m[15] = 1f
        return this
    }

    fun setTRS(p: Vec3, q: Quat, s: Vec3): Mat4 = setTRS(p.x, p.y, p.z, q, s.x, s.y, s.z)

    fun setTRS(px: Float, py: Float, pz: Float, q: Quat, sx: Float = 1f, sy: Float = 1f, sz: Float = 1f): Mat4 {
        val xx = q.x * q.x; val yy = q.y * q.y; val zz = q.z * q.z
        val xy = q.x * q.y; val xz = q.x * q.z; val yz = q.y * q.z
        val wx = q.w * q.x; val wy = q.w * q.y; val wz = q.w * q.z
        m[0] = (1f - 2f * (yy + zz)) * sx; m[1] = 2f * (xy + wz) * sx; m[2] = 2f * (xz - wy) * sx; m[3] = 0f
        m[4] = 2f * (xy - wz) * sy; m[5] = (1f - 2f * (xx + zz)) * sy; m[6] = 2f * (yz + wx) * sy; m[7] = 0f
        m[8] = 2f * (xz + wy) * sz; m[9] = 2f * (yz - wx) * sz; m[10] = (1f - 2f * (xx + yy)) * sz; m[11] = 0f
        m[12] = px; m[13] = py; m[14] = pz; m[15] = 1f
        return this
    }

    /** Uniform helpers for the very common "position + yaw + scale" case. */
    fun setPosYaw(px: Float, py: Float, pz: Float, yaw: Float, scale: Float = 1f): Mat4 {
        val c = cos(yaw) * scale; val s = sin(yaw) * scale
        m[0] = c; m[1] = 0f; m[2] = -s; m[3] = 0f
        m[4] = 0f; m[5] = scale; m[6] = 0f; m[7] = 0f
        m[8] = s; m[9] = 0f; m[10] = c; m[11] = 0f
        m[12] = px; m[13] = py; m[14] = pz; m[15] = 1f
        return this
    }

    fun setTranslation(x: Float, y: Float, z: Float): Mat4 { idt(); m[12] = x; m[13] = y; m[14] = z; return this }
    fun setScale(x: Float, y: Float, z: Float): Mat4 { idt(); m[0] = x; m[5] = y; m[10] = z; return this }

    fun translate(x: Float, y: Float, z: Float): Mat4 {
        m[12] += m[0] * x + m[4] * y + m[8] * z
        m[13] += m[1] * x + m[5] * y + m[9] * z
        m[14] += m[2] * x + m[6] * y + m[10] * z
        m[15] += m[3] * x + m[7] * y + m[11] * z
        return this
    }

    fun scale(x: Float, y: Float, z: Float): Mat4 {
        for (i in 0 until 4) { m[i] *= x; m[4 + i] *= y; m[8 + i] *= z }
        return this
    }

    fun perspective(fovYRad: Float, aspect: Float, near: Float, far: Float): Mat4 {
        val f = 1f / tan(fovYRad / 2f)
        m.fill(0f)
        m[0] = f / aspect
        m[5] = f
        m[10] = (far + near) / (near - far)
        m[11] = -1f
        m[14] = 2f * far * near / (near - far)
        return this
    }

    fun ortho(left: Float, right: Float, bottom: Float, top: Float, near: Float, far: Float): Mat4 {
        m.fill(0f)
        m[0] = 2f / (right - left)
        m[5] = 2f / (top - bottom)
        m[10] = -2f / (far - near)
        m[12] = -(right + left) / (right - left)
        m[13] = -(top + bottom) / (top - bottom)
        m[14] = -(far + near) / (far - near)
        m[15] = 1f
        return this
    }

    /** View matrix looking from [eye] at [target]. */
    fun lookAt(eye: Vec3, target: Vec3, up: Vec3): Mat4 =
        lookAt(eye.x, eye.y, eye.z, target.x, target.y, target.z, up.x, up.y, up.z)

    fun lookAt(ex: Float, ey: Float, ez: Float, tx: Float, ty: Float, tz: Float, ux: Float, uy: Float, uz: Float): Mat4 {
        var fx = tx - ex; var fy = ty - ey; var fz = tz - ez
        var l = MathX.length(fx, fy, fz)
        if (l < MathX.EPSILON) { fz = -1f; l = 1f }
        fx /= l; fy /= l; fz /= l
        var sx = fy * uz - fz * uy
        var sy = fz * ux - fx * uz
        var sz = fx * uy - fy * ux
        l = MathX.length(sx, sy, sz)
        if (l < MathX.EPSILON) {
            // Looking straight along the up vector: pick any perpendicular.
            sx = 1f; sy = 0f; sz = 0f; l = 1f
        }
        sx /= l; sy /= l; sz /= l
        val ux2 = sy * fz - sz * fy
        val uy2 = sz * fx - sx * fz
        val uz2 = sx * fy - sy * fx
        m[0] = sx; m[4] = sy; m[8] = sz; m[12] = -(sx * ex + sy * ey + sz * ez)
        m[1] = ux2; m[5] = uy2; m[9] = uz2; m[13] = -(ux2 * ex + uy2 * ey + uz2 * ez)
        m[2] = -fx; m[6] = -fy; m[10] = -fz; m[14] = fx * ex + fy * ey + fz * ez
        m[3] = 0f; m[7] = 0f; m[11] = 0f; m[15] = 1f
        return this
    }

    /** Inverts in place. Returns false (leaving the matrix untouched) when singular. */
    fun invert(): Boolean = invert(this)

    /** this = inverse(src). */
    fun invert(src: Mat4): Boolean {
        val a = src.m
        val inv = tmp
        inv[0] = a[5] * a[10] * a[15] - a[5] * a[11] * a[14] - a[9] * a[6] * a[15] + a[9] * a[7] * a[14] + a[13] * a[6] * a[11] - a[13] * a[7] * a[10]
        inv[4] = -a[4] * a[10] * a[15] + a[4] * a[11] * a[14] + a[8] * a[6] * a[15] - a[8] * a[7] * a[14] - a[12] * a[6] * a[11] + a[12] * a[7] * a[10]
        inv[8] = a[4] * a[9] * a[15] - a[4] * a[11] * a[13] - a[8] * a[5] * a[15] + a[8] * a[7] * a[13] + a[12] * a[5] * a[11] - a[12] * a[7] * a[9]
        inv[12] = -a[4] * a[9] * a[14] + a[4] * a[10] * a[13] + a[8] * a[5] * a[14] - a[8] * a[6] * a[13] - a[12] * a[5] * a[10] + a[12] * a[6] * a[9]
        inv[1] = -a[1] * a[10] * a[15] + a[1] * a[11] * a[14] + a[9] * a[2] * a[15] - a[9] * a[3] * a[14] - a[13] * a[2] * a[11] + a[13] * a[3] * a[10]
        inv[5] = a[0] * a[10] * a[15] - a[0] * a[11] * a[14] - a[8] * a[2] * a[15] + a[8] * a[3] * a[14] + a[12] * a[2] * a[11] - a[12] * a[3] * a[10]
        inv[9] = -a[0] * a[9] * a[15] + a[0] * a[11] * a[13] + a[8] * a[1] * a[15] - a[8] * a[3] * a[13] - a[12] * a[1] * a[11] + a[12] * a[3] * a[9]
        inv[13] = a[0] * a[9] * a[14] - a[0] * a[10] * a[13] - a[8] * a[1] * a[14] + a[8] * a[2] * a[13] + a[12] * a[1] * a[10] - a[12] * a[2] * a[9]
        inv[2] = a[1] * a[6] * a[15] - a[1] * a[7] * a[14] - a[5] * a[2] * a[15] + a[5] * a[3] * a[14] + a[13] * a[2] * a[7] - a[13] * a[3] * a[6]
        inv[6] = -a[0] * a[6] * a[15] + a[0] * a[7] * a[14] + a[4] * a[2] * a[15] - a[4] * a[3] * a[14] - a[12] * a[2] * a[7] + a[12] * a[3] * a[6]
        inv[10] = a[0] * a[5] * a[15] - a[0] * a[7] * a[13] - a[4] * a[1] * a[15] + a[4] * a[3] * a[13] + a[12] * a[1] * a[7] - a[12] * a[3] * a[5]
        inv[14] = -a[0] * a[5] * a[14] + a[0] * a[6] * a[13] + a[4] * a[1] * a[14] - a[4] * a[2] * a[13] - a[12] * a[1] * a[6] + a[12] * a[2] * a[5]
        inv[3] = -a[1] * a[6] * a[11] + a[1] * a[7] * a[10] + a[5] * a[2] * a[11] - a[5] * a[3] * a[10] - a[9] * a[2] * a[7] + a[9] * a[3] * a[6]
        inv[7] = a[0] * a[6] * a[11] - a[0] * a[7] * a[10] - a[4] * a[2] * a[11] + a[4] * a[3] * a[10] + a[8] * a[2] * a[7] - a[8] * a[3] * a[6]
        inv[11] = -a[0] * a[5] * a[11] + a[0] * a[7] * a[9] + a[4] * a[1] * a[11] - a[4] * a[3] * a[9] - a[8] * a[1] * a[7] + a[8] * a[3] * a[5]
        inv[15] = a[0] * a[5] * a[10] - a[0] * a[6] * a[9] - a[4] * a[1] * a[10] + a[4] * a[2] * a[9] + a[8] * a[1] * a[6] - a[8] * a[2] * a[5]
        val det = a[0] * inv[0] + a[1] * inv[4] + a[2] * inv[8] + a[3] * inv[12]
        if (abs(det) < 1e-12f) return false
        val invDet = 1f / det
        for (i in 0 until 16) m[i] = inv[i] * invDet
        return true
    }

    fun transpose(): Mat4 {
        fun swap(a: Int, b: Int) { val t = m[a]; m[a] = m[b]; m[b] = t }
        swap(1, 4); swap(2, 8); swap(3, 12); swap(6, 9); swap(7, 13); swap(11, 14)
        return this
    }

    /** Transforms a point (w = 1) in place, without perspective divide. */
    fun transformPoint(v: Vec3): Vec3 = v.set(
        m[0] * v.x + m[4] * v.y + m[8] * v.z + m[12],
        m[1] * v.x + m[5] * v.y + m[9] * v.z + m[13],
        m[2] * v.x + m[6] * v.y + m[10] * v.z + m[14],
    )

    /** Transforms a point with perspective divide (for projection matrices). */
    fun project(v: Vec3): Vec3 {
        val w = m[3] * v.x + m[7] * v.y + m[11] * v.z + m[15]
        val iw = if (abs(w) < MathX.EPSILON) 1f else 1f / w
        return v.set(
            (m[0] * v.x + m[4] * v.y + m[8] * v.z + m[12]) * iw,
            (m[1] * v.x + m[5] * v.y + m[9] * v.z + m[13]) * iw,
            (m[2] * v.x + m[6] * v.y + m[10] * v.z + m[14]) * iw,
        )
    }

    /** Transforms a direction (w = 0) in place. */
    fun transformDir(v: Vec3): Vec3 = v.set(
        m[0] * v.x + m[4] * v.y + m[8] * v.z,
        m[1] * v.x + m[5] * v.y + m[9] * v.z,
        m[2] * v.x + m[6] * v.y + m[10] * v.z,
    )

    fun getTranslation(out: Vec3): Vec3 = out.set(m[12], m[13], m[14])
    /** Largest axis scale (for bounding-sphere culling). */
    fun maxScale(): Float = sqrt(maxOf(
        m[0] * m[0] + m[1] * m[1] + m[2] * m[2],
        m[4] * m[4] + m[5] * m[5] + m[6] * m[6],
        m[8] * m[8] + m[9] * m[9] + m[10] * m[10],
    ))

    fun copy(): Mat4 = Mat4(m.copyOf())

    private val tmp = FloatArray(16)

    companion object {
        /** out = a * b on raw column-major arrays. `out` must not alias `a` or `b`. */
        fun mul(a: FloatArray, ao: Int, b: FloatArray, bo: Int, out: FloatArray, oo: Int = 0) {
            for (c in 0 until 4) {
                val b0 = b[bo + c * 4]; val b1 = b[bo + c * 4 + 1]; val b2 = b[bo + c * 4 + 2]; val b3 = b[bo + c * 4 + 3]
                out[oo + c * 4] = a[ao] * b0 + a[ao + 4] * b1 + a[ao + 8] * b2 + a[ao + 12] * b3
                out[oo + c * 4 + 1] = a[ao + 1] * b0 + a[ao + 5] * b1 + a[ao + 9] * b2 + a[ao + 13] * b3
                out[oo + c * 4 + 2] = a[ao + 2] * b0 + a[ao + 6] * b1 + a[ao + 10] * b2 + a[ao + 14] * b3
                out[oo + c * 4 + 3] = a[ao + 3] * b0 + a[ao + 7] * b1 + a[ao + 11] * b2 + a[ao + 15] * b3
            }
        }
    }
}
