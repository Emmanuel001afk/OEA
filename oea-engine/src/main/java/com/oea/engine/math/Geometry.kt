package com.oea.engine.math

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Axis-aligned bounding box. */
class Aabb(
    @JvmField var minX: Float = 0f, @JvmField var minY: Float = 0f, @JvmField var minZ: Float = 0f,
    @JvmField var maxX: Float = 0f, @JvmField var maxY: Float = 0f, @JvmField var maxZ: Float = 0f,
) {
    fun set(minX: Float, minY: Float, minZ: Float, maxX: Float, maxY: Float, maxZ: Float): Aabb {
        this.minX = minX; this.minY = minY; this.minZ = minZ; this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ
        return this
    }
    fun set(o: Aabb): Aabb = set(o.minX, o.minY, o.minZ, o.maxX, o.maxY, o.maxZ)
    /** Box from centre and half extents. */
    fun setCentered(cx: Float, cy: Float, cz: Float, hx: Float, hy: Float, hz: Float): Aabb =
        set(cx - hx, cy - hy, cz - hz, cx + hx, cy + hy, cz + hz)
    fun empty(): Aabb = set(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
    fun isEmpty() = minX > maxX
    fun extend(x: Float, y: Float, z: Float): Aabb {
        if (x < minX) minX = x; if (y < minY) minY = y; if (z < minZ) minZ = z
        if (x > maxX) maxX = x; if (y > maxY) maxY = y; if (z > maxZ) maxZ = z
        return this
    }
    fun extend(o: Aabb): Aabb { extend(o.minX, o.minY, o.minZ); return extend(o.maxX, o.maxY, o.maxZ) }
    val centerX get() = (minX + maxX) * 0.5f
    val centerY get() = (minY + maxY) * 0.5f
    val centerZ get() = (minZ + maxZ) * 0.5f
    val sizeX get() = maxX - minX
    val sizeY get() = maxY - minY
    val sizeZ get() = maxZ - minZ
    fun radius(): Float = 0.5f * MathX.length(sizeX, sizeY, sizeZ)

    fun overlaps(o: Aabb): Boolean =
        minX < o.maxX && maxX > o.minX && minY < o.maxY && maxY > o.minY && minZ < o.maxZ && maxZ > o.minZ

    fun contains(x: Float, y: Float, z: Float): Boolean =
        x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ

    fun translate(dx: Float, dy: Float, dz: Float): Aabb = set(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz)
    fun inflate(r: Float): Aabb = set(minX - r, minY - r, minZ - r, maxX + r, maxY + r, maxZ + r)

    /** Closest point inside the box to (x, y, z). */
    fun closestPoint(x: Float, y: Float, z: Float, out: Vec3): Vec3 =
        out.set(MathX.clamp(x, minX, maxX), MathX.clamp(y, minY, maxY), MathX.clamp(z, minZ, maxZ))

    fun copy() = Aabb(minX, minY, minZ, maxX, maxY, maxZ)
    override fun toString() = "Aabb[$minX,$minY,$minZ .. $maxX,$maxY,$maxZ]"
}

class Ray(@JvmField val origin: Vec3 = Vec3(), @JvmField val dir: Vec3 = Vec3(0f, 0f, -1f)) {
    fun set(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float): Ray {
        origin.set(ox, oy, oz); dir.set(dx, dy, dz).nor(); return this
    }
    fun set(o: Vec3, d: Vec3): Ray = set(o.x, o.y, o.z, d.x, d.y, d.z)
    fun at(t: Float, out: Vec3): Vec3 = out.set(origin.x + dir.x * t, origin.y + dir.y * t, origin.z + dir.z * t)
}

/** Plane `normal · p + d = 0`. */
class Plane(@JvmField val normal: Vec3 = Vec3(0f, 1f, 0f), @JvmField var d: Float = 0f) {
    fun set(nx: Float, ny: Float, nz: Float, d: Float): Plane {
        val l = MathX.length(nx, ny, nz)
        normal.set(nx / l, ny / l, nz / l)
        this.d = d / l
        return this
    }
    fun distance(x: Float, y: Float, z: Float): Float = normal.x * x + normal.y * y + normal.z * z + d
}

/** View frustum extracted from a view-projection matrix, for culling. */
class Frustum {
    val planes = Array(6) { Plane() }

    fun update(viewProj: Mat4) {
        val m = viewProj.m
        // Gribb/Hartmann plane extraction (rows of the column-major matrix).
        planes[0].set(m[3] + m[0], m[7] + m[4], m[11] + m[8], m[15] + m[12])   // left
        planes[1].set(m[3] - m[0], m[7] - m[4], m[11] - m[8], m[15] - m[12])   // right
        planes[2].set(m[3] + m[1], m[7] + m[5], m[11] + m[9], m[15] + m[13])   // bottom
        planes[3].set(m[3] - m[1], m[7] - m[5], m[11] - m[9], m[15] - m[13])   // top
        planes[4].set(m[3] + m[2], m[7] + m[6], m[11] + m[10], m[15] + m[14])  // near
        planes[5].set(m[3] - m[2], m[7] - m[6], m[11] - m[10], m[15] - m[14])  // far
    }

    fun sphereVisible(x: Float, y: Float, z: Float, r: Float): Boolean {
        for (p in planes) if (p.distance(x, y, z) < -r) return false
        return true
    }

    fun aabbVisible(b: Aabb): Boolean {
        for (p in planes) {
            val px = if (p.normal.x >= 0f) b.maxX else b.minX
            val py = if (p.normal.y >= 0f) b.maxY else b.minY
            val pz = if (p.normal.z >= 0f) b.maxZ else b.minZ
            if (p.distance(px, py, pz) < 0f) return false
        }
        return true
    }
}

/** Analytic intersection tests. Ray directions must be normalised; results are distances along the ray or -1. */
object Intersect {
    fun rayAabb(ray: Ray, b: Aabb, maxDist: Float = Float.MAX_VALUE): Float =
        rayAabb(ray.origin.x, ray.origin.y, ray.origin.z, ray.dir.x, ray.dir.y, ray.dir.z, b, maxDist)

    fun rayAabb(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, b: Aabb, maxDist: Float = Float.MAX_VALUE): Float {
        var tMin = 0f
        var tMax = maxDist
        // X slab
        if (abs(dx) < 1e-9f) {
            if (ox < b.minX || ox > b.maxX) return -1f
        } else {
            val inv = 1f / dx
            var t1 = (b.minX - ox) * inv; var t2 = (b.maxX - ox) * inv
            if (t1 > t2) { val t = t1; t1 = t2; t2 = t }
            tMin = max(tMin, t1); tMax = min(tMax, t2)
            if (tMin > tMax) return -1f
        }
        if (abs(dy) < 1e-9f) {
            if (oy < b.minY || oy > b.maxY) return -1f
        } else {
            val inv = 1f / dy
            var t1 = (b.minY - oy) * inv; var t2 = (b.maxY - oy) * inv
            if (t1 > t2) { val t = t1; t1 = t2; t2 = t }
            tMin = max(tMin, t1); tMax = min(tMax, t2)
            if (tMin > tMax) return -1f
        }
        if (abs(dz) < 1e-9f) {
            if (oz < b.minZ || oz > b.maxZ) return -1f
        } else {
            val inv = 1f / dz
            var t1 = (b.minZ - oz) * inv; var t2 = (b.maxZ - oz) * inv
            if (t1 > t2) { val t = t1; t1 = t2; t2 = t }
            tMin = max(tMin, t1); tMax = min(tMax, t2)
            if (tMin > tMax) return -1f
        }
        return tMin
    }

    /** Normal of the AABB face hit at point (px, py, pz). */
    fun aabbNormal(b: Aabb, px: Float, py: Float, pz: Float, out: Vec3): Vec3 {
        val dxMin = abs(px - b.minX); val dxMax = abs(px - b.maxX)
        val dyMin = abs(py - b.minY); val dyMax = abs(py - b.maxY)
        val dzMin = abs(pz - b.minZ); val dzMax = abs(pz - b.maxZ)
        var best = dxMin; out.set(-1f, 0f, 0f)
        if (dxMax < best) { best = dxMax; out.set(1f, 0f, 0f) }
        if (dyMin < best) { best = dyMin; out.set(0f, -1f, 0f) }
        if (dyMax < best) { best = dyMax; out.set(0f, 1f, 0f) }
        if (dzMin < best) { best = dzMin; out.set(0f, 0f, -1f) }
        if (dzMax < best) { out.set(0f, 0f, 1f) }
        return out
    }

    fun raySphere(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, cx: Float, cy: Float, cz: Float, r: Float): Float {
        val lx = ox - cx; val ly = oy - cy; val lz = oz - cz
        val b = lx * dx + ly * dy + lz * dz
        val c = lx * lx + ly * ly + lz * lz - r * r
        if (c > 0f && b > 0f) return -1f
        val disc = b * b - c
        if (disc < 0f) return -1f
        val t = -b - sqrt(disc)
        return if (t < 0f) 0f else t
    }

    /** Ray vs capsule with segment (a, b) and radius r (Inigo Quilez). */
    fun rayCapsule(
        ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float,
        ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, r: Float,
    ): Float {
        val bax = bx - ax; val bay = by - ay; val baz = bz - az
        val oax = ox - ax; val oay = oy - ay; val oaz = oz - az
        val baba = bax * bax + bay * bay + baz * baz
        val bard = bax * dx + bay * dy + baz * dz
        val baoa = bax * oax + bay * oay + baz * oaz
        val rdoa = dx * oax + dy * oay + dz * oaz
        val oaoa = oax * oax + oay * oay + oaz * oaz
        val a = baba - bard * bard
        var b = baba * rdoa - baoa * bard
        var c = baba * oaoa - baoa * baoa - r * r * baba
        var h = b * b - a * c
        if (h >= 0f && abs(a) > 1e-9f) {
            val t = (-b - sqrt(h)) / a
            val y = baoa + t * bard
            if (y > 0f && y < baba && t >= 0f) return t
            // Caps.
            val ocx = if (y <= 0f) oax else ox - bx
            val ocy = if (y <= 0f) oay else oy - by
            val ocz = if (y <= 0f) oaz else oz - bz
            b = dx * ocx + dy * ocy + dz * ocz
            c = ocx * ocx + ocy * ocy + ocz * ocz - r * r
            h = b * b - c
            if (h > 0f) {
                val tc = -b - sqrt(h)
                if (tc >= 0f) return tc
            }
            return -1f
        }
        // Parallel to the axis: test both cap spheres.
        val t1 = raySphere(ox, oy, oz, dx, dy, dz, ax, ay, az, r)
        val t2 = raySphere(ox, oy, oz, dx, dy, dz, bx, by, bz, r)
        return when {
            t1 < 0f -> t2
            t2 < 0f -> t1
            else -> min(t1, t2)
        }
    }

    /** Ray vs a vertical cylinder (axis along Y) between [y0] and [y1]. */
    fun rayCylinderY(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, cx: Float, cz: Float, r: Float, y0: Float, y1: Float): Float {
        val px = ox - cx; val pz = oz - cz
        val a = dx * dx + dz * dz
        if (a < 1e-9f) {
            if (px * px + pz * pz > r * r) return -1f
            if (oy in y0..y1) return 0f
            val t = if (dy > 0f) (y0 - oy) / dy else (y1 - oy) / dy
            return if (t >= 0f) t else -1f
        }
        val b = px * dx + pz * dz
        val c = px * px + pz * pz - r * r
        val disc = b * b - a * c
        if (disc < 0f) return -1f
        val sq = sqrt(disc)
        var t = (-b - sq) / a
        if (t < 0f) {
            if (c <= 0f) {
                // Inside the infinite cylinder.
                return if (oy in y0..y1) 0f else -1f
            }
            t = (-b + sq) / a
            if (t < 0f) return -1f
        }
        val y = oy + dy * t
        if (y in y0..y1) return t
        // Cap planes.
        val plane = if (y < y0) y0 else y1
        if (abs(dy) < 1e-9f) return -1f
        val tc = (plane - oy) / dy
        if (tc < 0f) return -1f
        val hx = ox + dx * tc - cx; val hz = oz + dz * tc - cz
        return if (hx * hx + hz * hz <= r * r) tc else -1f
    }

    fun rayPlaneY(oy: Float, dy: Float, planeY: Float): Float {
        if (abs(dy) < 1e-9f) return -1f
        val t = (planeY - oy) / dy
        return if (t >= 0f) t else -1f
    }

    /** Closest point parameter on segment AB to point P (0..1). */
    fun closestOnSegment(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, px: Float, py: Float, pz: Float): Float {
        val abx = bx - ax; val aby = by - ay; val abz = bz - az
        val l2 = abx * abx + aby * aby + abz * abz
        if (l2 < 1e-12f) return 0f
        return MathX.clamp01(((px - ax) * abx + (py - ay) * aby + (pz - az) * abz) / l2)
    }

    /** Squared distance from point to AABB. */
    fun pointAabbDist2(px: Float, py: Float, pz: Float, b: Aabb): Float {
        val dx = max(max(b.minX - px, 0f), px - b.maxX)
        val dy = max(max(b.minY - py, 0f), py - b.maxY)
        val dz = max(max(b.minZ - pz, 0f), pz - b.maxZ)
        return dx * dx + dy * dy + dz * dz
    }
}
