package com.oea.engine.fx

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Coarse Eulerian fluid grid — the physics behind smoke, fog, fire and magic.
 *
 * Semi-Lagrangian advection carries velocity and density; buoyancy lifts hot
 * gas; vorticity confinement restores the little curls that make smoke *swirl*
 * instead of smearing; dissipation lets it break up naturally. Solid cells
 * (terrain, walls) kill velocity and block density, so fog hugs the floor
 * around a rock and smoke piles against a wall before rising over it.
 *
 * The grid is deliberately small (a few thousand cells) and stepped at a fixed
 * substep: it is a *plausible* fluid, tuned for phones, not a research solver.
 * Particles sample [sampleVelocity] every frame and ride the same air.
 */
class FluidGrid(
    /** World-space centre of the volume. */
    var centerX: Float = 0f,
    var centerZ: Float = 0f,
    /** Cells across X/Z and vertically. */
    val nx: Int = 36,
    val ny: Int = 14,
    val nz: Int = 36,
    /** Cell size in metres. */
    val cell: Float = 1.6f,
) {
    private val size = nx * ny * nz
    private val vx = FloatArray(size)
    private val vy = FloatArray(size)
    private val vz = FloatArray(size)
    private val vxB = FloatArray(size)
    private val vyB = FloatArray(size)
    private val vzB = FloatArray(size)
    private val dens = FloatArray(size)
    private val densB = FloatArray(size)
    private val temp = FloatArray(size)
    private val tempB = FloatArray(size)
    private val solid = BooleanArray(size)

    /** Ambient wind the whole volume drifts with (weather). */
    var windX = 0f
    var windZ = 0f
    /** How fast swirls fade (0.1..3). */
    var dissipation = 0.55f
    /** Curl restoring force. */
    var vorticity = 2.2f
    /** Upward force per unit of temperature. */
    var buoyancy = 3.2f
    private var time = 0f

    val originX get() = centerX - nx * cell * 0.5f
    val originZ get() = centerZ - nz * cell * 0.5f
    val topY get() = ny * cell

    private fun idx(i: Int, j: Int, k: Int) = (j * nz + k) * nx + i

    /** Rebuilds the solid mask from a height query (terrain, floors). */
    fun buildObstacles(heightAt: (Float, Float) -> Float) {
        val ox = originX; val oz = originZ
        for (j in 0 until ny) {
            val y = j * cell + cell * 0.5f
            for (k in 0 until nz) for (i in 0 until nx) {
                val x = ox + (i + 0.5f) * cell
                val z = oz + (k + 0.5f) * cell
                solid[idx(i, j, k)] = heightAt(x, z) > y
            }
        }
    }

    /** Marks a world AABB solid (walls, props) or air again. */
    fun setSolidBox(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, isSolid: Boolean = true) {
        val ox = originX; val oz = originZ
        val i0 = max(0, ((x0 - ox) / cell).toInt()); val i1 = min(nx - 1, ((x1 - ox) / cell).toInt())
        val j0 = max(0, (y0 / cell).toInt()); val j1 = min(ny - 1, (y1 / cell).toInt())
        val k0 = max(0, ((z0 - oz) / cell).toInt()); val k1 = min(nz - 1, ((z1 - oz) / cell).toInt())
        for (j in j0..j1) for (k in k0..k1) for (i in i0..i1) solid[idx(i, j, k)] = isSolid
    }

    /** Injects hot smoke (density + temperature) at a world point. */
    fun addSmoke(x: Float, y: Float, z: Float, amount: Float, heat: Float = 1f) {
        val ox = originX; val oz = originZ
        val i = ((x - ox) / cell).toInt(); val j = (y / cell).toInt(); val k = ((z - oz) / cell).toInt()
        if (i < 0 || j < 0 || k < 0 || i >= nx || j >= ny || k >= nz) return
        val id = idx(i, j, k)
        if (solid[id]) return
        dens[id] = min(1.6f, dens[id] + amount)
        temp[id] = min(2.2f, temp[id] + heat)
    }

    /** Adds momentum at a world point (explosions, gusts, wingbeats). */
    fun addImpulse(x: Float, y: Float, z: Float, ix: Float, iy: Float, iz: Float, radius: Float = 1.4f) {
        val ox = originX; val oz = originZ
        val cr = max(1, (radius / cell).toInt())
        val ci = ((x - ox) / cell).toInt(); val cj = (y / cell).toInt(); val ck = ((z - oz) / cell).toInt()
        for (dj in -cr..cr) for (dk in -cr..cr) for (di in -cr..cr) {
            val i = ci + di; val j = cj + dj; val k = ck + dk
            if (i < 0 || j < 0 || k < 0 || i >= nx || j >= ny || k >= nz) continue
            val id = idx(i, j, k)
            if (solid[id]) continue
            val fall = 1f - (abs(di) + abs(dj) + abs(dk)).toFloat() / (cr * 3f + 1f)
            if (fall <= 0f) continue
            vx[id] += ix * fall; vy[id] += iy * fall; vz[id] += iz * fall
        }
    }

    /** Samples the air velocity at a world point into [out] = (vx, vy, vz). */
    fun sampleVelocity(x: Float, y: Float, z: Float, out: FloatArray) {
        val ox = originX; val oz = originZ
        val fx = (x - ox) / cell - 0.5f; val fy = y / cell - 0.5f; val fz = (z - oz) / cell - 0.5f
        val i = fx.toInt(); val j = fy.toInt(); val k = fz.toInt()
        if (i < 0 || j < 0 || k < 0 || i >= nx - 1 || j >= ny - 1 || k >= nz - 1) {
            out[0] = windX; out[1] = 0f; out[2] = windZ
            return
        }
        val tx = fx - i; val ty = fy - j; val tz = fz - k
        fun vxAt(ii: Int, jj: Int, kk: Int) = if (solid[idx(ii, jj, kk)]) 0f else vx[idx(ii, jj, kk)]
        fun vyAt(ii: Int, jj: Int, kk: Int) = if (solid[idx(ii, jj, kk)]) 0f else vy[idx(ii, jj, kk)]
        fun vzAt(ii: Int, jj: Int, kk: Int) = if (solid[idx(ii, jj, kk)]) 0f else vz[idx(ii, jj, kk)]
        out[0] = lerp3(lerp3(lerp3(vxAt(i, j, k), vxAt(i + 1, j, k), tx), lerp3(vxAt(i, j + 1, k), vxAt(i + 1, j + 1, k), tx), ty),
            lerp3(lerp3(vxAt(i, j, k + 1), vxAt(i + 1, j, k + 1), tx), lerp3(vxAt(i, j + 1, k + 1), vxAt(i + 1, j + 1, k + 1), tx), ty), tz)
        out[1] = lerp3(lerp3(lerp3(vyAt(i, j, k), vyAt(i + 1, j, k), tx), lerp3(vyAt(i, j + 1, k), vyAt(i + 1, j + 1, k), tx), ty),
            lerp3(lerp3(vyAt(i, j, k + 1), vyAt(i + 1, j, k + 1), tx), lerp3(vyAt(i, j + 1, k + 1), vyAt(i + 1, j + 1, k + 1), tx), ty), tz)
        out[2] = lerp3(lerp3(lerp3(vzAt(i, j, k), vzAt(i + 1, j, k), tx), lerp3(vzAt(i, j + 1, k), vzAt(i + 1, j + 1, k), tx), ty),
            lerp3(lerp3(vzAt(i, j, k + 1), vzAt(i + 1, j, k + 1), tx), lerp3(vzAt(i, j + 1, k + 1), vzAt(i + 1, j + 1, k + 1), tx), ty), tz)
        out[0] += windX; out[2] += windZ
    }

    /** Solid obstacle query for particle collision (terrain, walls and roofs). */
    fun isSolidAt(x: Float, y: Float, z: Float): Boolean {
        val i = ((x - originX) / cell).toInt()
        val j = (y / cell).toInt()
        val k = ((z - originZ) / cell).toInt()
        return i in 0 until nx && j in 0 until ny && k in 0 until nz && solid[idx(i, j, k)]
    }

    /** Smoke density at a world point (0..~1.5) — used for smoke shading. */
    fun densityAt(x: Float, y: Float, z: Float): Float {
        val ox = originX; val oz = originZ
        val i = ((x - ox) / cell).toInt(); val j = (y / cell).toInt(); val k = ((z - oz) / cell).toInt()
        if (i < 0 || j < 0 || k < 0 || i >= nx || j >= ny || k >= nz) return 0f
        return dens[idx(i, j, k)]
    }

    private fun lerp3(a: Float, b: Float, t: Float) = a + (b - a) * t

    /**
     * Advances the fluid. [dt] is clamped internally and run as fixed substeps
     * so a hitch cannot blow the grid up.
     */
    fun step(dt: Float) {
        val steps = if (dt > 0.033f) 2 else 1
        val h = min(dt, 0.05f) / steps
        repeat(steps) { substep(h) }
        time += dt
    }

    private fun substep(h: Float) {
        // 1) Forces: buoyancy + ambient wind + gentle curl turbulence.
        for (j in 0 until ny) for (k in 0 until nz) for (i in 0 until nx) {
            val id = idx(i, j, k)
            if (solid[id]) {
                vx[id] = 0f; vy[id] = 0f; vz[id] = 0f
                continue
            }
            val t = temp[id]
            vy[id] += buoyancy * t * h
            val gust = turbulence(i, j, k, time)
            vx[id] += (windX * 0.4f + gust[0] - vx[id] * 0.22f) * h * 2.4f
            vz[id] += (windZ * 0.4f + gust[1] - vz[id] * 0.22f) * h * 2.4f
            vy[id] += (gust[2] - vy[id] * 0.12f) * h * 1.2f
        }
        // 2) Vorticity confinement: re-inject the small curls advection eats.
        if (vorticity > 0f) {
            for (j in 1 until ny - 1) for (k in 1 until nz - 1) for (i in 1 until nx - 1) {
                val id = idx(i, j, k)
                if (solid[id]) continue
                val curlY = (vz[idx(i, j, k + 1)] - vz[idx(i, j, k - 1)]) - (vx[idx(i, j + 1, k)] - vx[idx(i, j - 1, k)])
                val curlX = (vy[idx(i, j, k + 1)] - vy[idx(i, j, k - 1)]) - (vz[idx(i, j + 1, k)] - vz[idx(i, j - 1, k)])
                val curlZ = (vx[idx(i, j + 1, k)] - vx[idx(i, j - 1, k)]) - (vy[idx(i, j, k + 1)] - vy[idx(i, j, k - 1)])
                val m = abs(curlX) + abs(curlY) + abs(curlZ) + 1e-3f
                vx[id] += vorticity * (curlY / m) * h
                vy[id] += vorticity * (curlZ / m) * h * 0.5f
                vz[id] += vorticity * (curlX / m) * h
            }
        }
        // 3) Advect velocity, then density + heat.
        advectVec(h)
        advectScalar(dens, densB, h, dissipation)
        advectScalar(temp, tempB, h, dissipation * 1.7f)
        // 4) Walls win: solids stay solid after transport.
        for (id in 0 until size) if (solid[id]) {
            vx[id] = 0f; vy[id] = 0f; vz[id] = 0f
            dens[id] = 0f; temp[id] = 0f
        }
    }

    private fun advectVec(h: Float) {
        for (j in 0 until ny) for (k in 0 until nz) for (i in 0 until nx) {
            val id = idx(i, j, k)
            if (solid[id]) continue
            val x = i + 0.5f - vx[id] * h / cell
            val y = j + 0.5f - vy[id] * h / cell
            val z = k + 0.5f - vz[id] * h / cell
            vxB[id] = sampleTri(vx, x, y, z, i, j, k)
            vyB[id] = sampleTri(vy, x, y, z, i, j, k)
            vzB[id] = sampleTri(vz, x, y, z, i, j, k)
        }
        for (id in 0 until size) {
            vx[id] = vxB[id]; vy[id] = vyB[id]; vz[id] = vzB[id]
        }
    }

    private fun advectScalar(f: FloatArray, buf: FloatArray, h: Float, fade: Float) {
        for (j in 0 until ny) for (k in 0 until nz) for (i in 0 until nx) {
            val id = idx(i, j, k)
            if (solid[id]) { buf[id] = 0f; continue }
            val x = i + 0.5f - vx[id] * h / cell
            val y = j + 0.5f - vy[id] * h / cell
            val z = k + 0.5f - vz[id] * h / cell
            buf[id] = sampleTri(f, x, y, z, i, j, k) * (1f - fade * h)
        }
        for (id in 0 until size) f[id] = buf[id]
    }

    private fun sampleTri(f: FloatArray, x: Float, y: Float, z: Float, i: Int, j: Int, k: Int): Float {
        val i1 = min(nx - 1, max(0, x.toInt())); val j1 = min(ny - 1, max(0, y.toInt())); val k1 = min(nz - 1, max(0, z.toInt()))
        val i0 = (if (x < i1) i1 - 1 else i1).coerceIn(0, nx - 1)
        val j0 = (if (y < j1) j1 - 1 else j1).coerceIn(0, ny - 1)
        val k0 = (if (z < k1) k1 - 1 else k1).coerceIn(0, nz - 1)
        val i2 = (i0 + 1).coerceIn(0, nx - 1); val j2 = (j0 + 1).coerceIn(0, ny - 1); val k2 = (k0 + 1).coerceIn(0, nz - 1)
        val tx = (x - i0).coerceIn(0f, 1f); val ty = (y - j0).coerceIn(0f, 1f); val tz = (z - k0).coerceIn(0f, 1f)
        val c00 = lerp3(f[idx(i0, j0, k0)], f[idx(i2, j0, k0)], tx)
        val c10 = lerp3(f[idx(i0, j2, k0)], f[idx(i2, j2, k0)], tx)
        val c01 = lerp3(f[idx(i0, j0, k2)], f[idx(i2, j0, k2)], tx)
        val c11 = lerp3(f[idx(i0, j2, k2)], f[idx(i2, j2, k2)], tx)
        return lerp3(lerp3(c00, c10, ty), lerp3(c01, c11, ty), tz)
    }

    private val gust = FloatArray(3)

    /** Cheap analytic curl noise — free turbulence without a second solver. */
    private fun turbulence(i: Int, j: Int, k: Int, t: Float): FloatArray {
        val s = 0.35f
        gust[0] = (noise3(i * 0.17f + t * s, j * 0.23f, k * 0.15f)) * 1.1f
        gust[1] = (noise3(i * 0.13f, j * 0.19f + t * s * 0.7f, k * 0.21f)) * 0.45f
        gust[2] = (noise3(i * 0.11f + 5f, j * 0.15f, k * 0.19f + t * s * 0.9f)) * 1.1f
        return gust
    }

    private fun noise3(x: Float, y: Float, z: Float): Float {
        val a = sin(x * 2.1f + z * 1.3f + y * 0.7f)
        val b = sin(x * 4.7f - y * 3.1f + z * 2.3f) * 0.5f
        val c = sin(z * 8.3f + y * 5.7f - x * 6.1f) * 0.25f
        return (a + b + c) * 0.55f
    }
}
