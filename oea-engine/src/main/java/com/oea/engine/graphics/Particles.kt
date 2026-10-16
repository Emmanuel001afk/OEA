package com.oea.engine.graphics

import android.opengl.GLES30
import com.oea.engine.math.Colors
import com.oea.engine.math.MathX
import com.oea.engine.math.Rng
import com.oea.engine.math.Vec3
import java.nio.FloatBuffer
import kotlin.math.atan2
import kotlin.math.sqrt

/** Parameters for [ParticleSystem.burst]. Ranges are min..max. */
data class ParticlePreset(
    val speedMin: Float, val speedMax: Float,
    val lifeMin: Float, val lifeMax: Float,
    val sizeStart: Float, val sizeEnd: Float,
    val colorStart: Int, val colorEnd: Int,
    val gravity: Float = 0f,
    val drag: Float = 0f,
    val spread: Float = 1f,
    /** Bias added to the emission direction (e.g. up for fountains). */
    val upBias: Float = 0f,
    val spin: Float = 0f,
) {
    companion object {
        val SPARKS = ParticlePreset(4f, 11f, 0.18f, 0.45f, 0.09f, 0.02f, 0xFFFFE9A8.toInt(), 0x00FF6A00, gravity = -9f, drag = 1.5f)
        val DUST = ParticlePreset(0.4f, 1.6f, 0.5f, 1.1f, 0.25f, 0.9f, 0x99C9B9A0.toInt(), 0x00C9B9A0, gravity = 0.3f, drag = 2f, upBias = 0.4f)
        val SMOKE = ParticlePreset(0.3f, 1.2f, 1.2f, 2.4f, 0.4f, 1.8f, 0x88505050.toInt(), 0x00303030, gravity = 0.8f, drag = 1f, upBias = 0.8f, spin = 1f)
        val COIN_SPARKLE = ParticlePreset(1f, 3f, 0.25f, 0.5f, 0.16f, 0.0f, 0xFFFFF3B0.toInt(), 0x00FFC400, gravity = 0f, drag = 3f)
        val CONFETTI = ParticlePreset(3f, 8f, 1.2f, 2.2f, 0.12f, 0.1f, Colors.WHITE, 0x00FFFFFF, gravity = -6f, drag = 1.2f, upBias = 1.2f, spin = 8f)
        val BLOOD_HIT = ParticlePreset(1.5f, 4f, 0.2f, 0.4f, 0.12f, 0.05f, 0xFFFF5A3C.toInt(), 0x00B01010, gravity = -8f, drag = 2f)
        val MUZZLE = ParticlePreset(0.5f, 2f, 0.04f, 0.08f, 0.35f, 0.15f, 0xFFFFE6A0.toInt(), 0x00FF8000, drag = 4f)
    }
}

/**
 * CPU-simulated, GPU-instanced billboard particles. Each system has one
 * texture and blend mode; quality settings scale emission counts.
 */
class ParticleSystem(
    val maxParticles: Int = 2048,
    val texture: Texture = Textures.softCircle(),
    val additive: Boolean = true,
    /** Stretch and orient particles along their velocity (sparks, rain). */
    val velocityAligned: Boolean = false,
) {
    private val px = FloatArray(maxParticles); private val py = FloatArray(maxParticles); private val pz = FloatArray(maxParticles)
    private val vx = FloatArray(maxParticles); private val vy = FloatArray(maxParticles); private val vz = FloatArray(maxParticles)
    private val life = FloatArray(maxParticles); private val maxLife = FloatArray(maxParticles)
    private val size0 = FloatArray(maxParticles); private val size1 = FloatArray(maxParticles)
    private val color0 = IntArray(maxParticles); private val color1 = IntArray(maxParticles)
    private val rot = FloatArray(maxParticles); private val spin = FloatArray(maxParticles)
    private val gravity = FloatArray(maxParticles); private val drag = FloatArray(maxParticles)
    var count = 0
        private set
    /** 0..1, set from quality settings by the game/engine; scales [burst] counts. */
    var density = 1f
    /**
     * World physics hooks (VFX pipeline). When set, particles are advected by
     * the sampled wind/fluid field and bounce off the ground instead of
     * passing through it. Null keeps the classic canned behaviour.
     */
    /** Samples the local wind/fluid velocity into [out] (vx, vy, vz). */
    var wind: ((x: Float, y: Float, z: Float, out: FloatArray) -> Unit)? = null
    /** Ground height at (x, z); particles collide when below it. */
    var ground: ((x: Float, z: Float) -> Float)? = null
    /** Solid-volume query for walls/props; the particle is rewound and reflected. */
    var obstacle: ((x: Float, y: Float, z: Float) -> Boolean)? = null
    /** Restitution for ground bounces; 0 kills vertical speed (sandbag). */
    var bounce = 0f
    /** Extra upward force (fire, smoke): added to gravity per step. */
    var buoyancy = 0f
    private val windOut = FloatArray(3)
    private val rng = Rng(0x5EED)
    private var densityCarry = 0f

    private val data = FloatArray(maxParticles * FLOATS)
    private var buffer: FloatBuffer? = null
    private var vao = 0
    private var cornerVbo = 0
    private var instanceVbo = 0
    private var generation = -1
    private val lin0 = FloatArray(4)
    private val lin1 = FloatArray(4)
    private val right = Vec3()
    private val up = Vec3()

    fun clear() { count = 0 }

    /** Adds one particle; silently ignored when full. */
    fun emit(
        x: Float, y: Float, z: Float, vx: Float, vy: Float, vz: Float,
        lifetime: Float, sizeStart: Float, sizeEnd: Float, colorStart: Int, colorEnd: Int,
        gravity: Float = 0f, drag: Float = 0f, rotation: Float = rng.nextFloat() * MathX.TAU, spin: Float = 0f,
    ) {
        if (count >= maxParticles || lifetime <= 0f) return
        val i = count++
        px[i] = x; py[i] = y; pz[i] = z
        this.vx[i] = vx; this.vy[i] = vy; this.vz[i] = vz
        life[i] = 0f; maxLife[i] = lifetime
        size0[i] = sizeStart; size1[i] = sizeEnd
        color0[i] = colorStart; color1[i] = colorEnd
        rot[i] = rotation; this.spin[i] = spin
        this.gravity[i] = gravity; this.drag[i] = drag
    }

    /** Emits about [count] particles (scaled by [density]) in a sphere, biased by the preset. */
    fun burst(preset: ParticlePreset, x: Float, y: Float, z: Float, count: Int, dirX: Float = 0f, dirY: Float = 0f, dirZ: Float = 0f, tint: Int = 0) {
        densityCarry += count * density
        val n = densityCarry.toInt()
        densityCarry -= n
        for (k in 0 until n) {
            var dx = rng.range(-1f, 1f); var dy = rng.range(-1f, 1f); var dz = rng.range(-1f, 1f)
            val l = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-3f)
            dx = dx / l * preset.spread + dirX; dy = dy / l * preset.spread + dirY + preset.upBias; dz = dz / l * preset.spread + dirZ
            val dl = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-3f)
            val speed = rng.range(preset.speedMin, preset.speedMax)
            val c0 = if (tint != 0) Colors.withAlpha(tint, Colors.alpha(preset.colorStart)) else preset.colorStart
            val c1 = if (tint != 0) Colors.withAlpha(tint, Colors.alpha(preset.colorEnd)) else preset.colorEnd
            emit(x, y, z, dx / dl * speed, dy / dl * speed, dz / dl * speed, rng.range(preset.lifeMin, preset.lifeMax),
                preset.sizeStart, preset.sizeEnd, c0, c1, preset.gravity, preset.drag, spin = rng.range(-preset.spin, preset.spin))
        }
    }

    fun update(dt: Float) {
        var i = 0
        while (i < count) {
            life[i] += dt
            if (life[i] >= maxLife[i]) {
                val last = --count
                if (i != last) copy(last, i)
                continue
            }
            val d = 1f / (1f + drag[i] * dt)
            vx[i] *= d; vy[i] = (vy[i] + (gravity[i] + buoyancy) * dt) * d; vz[i] *= d
            // Wind/fluid advection: the air carries the particle, not the other way round.
            val w = wind
            if (w != null) {
                w(px[i], py[i], pz[i], windOut)
                val k = dt * 1.6f / (1f + drag[i] * 0.6f)
                vx[i] += (windOut[0] - vx[i] * 0.25f) * k
                vy[i] += (windOut[1] - vy[i] * 0.15f) * k
                vz[i] += (windOut[2] - vz[i] * 0.25f) * k
            }
            px[i] += vx[i] * dt; py[i] += vy[i] * dt; pz[i] += vz[i] * dt
            // Solid-volume collision: rewind through a wall/prop and reflect
            // the velocity. It is deliberately cheap and stable for phones;
            // the fluid grid supplies the detailed smoke flow around the wall.
            val wall = obstacle
            if (wall != null && wall(px[i], py[i], pz[i])) {
                px[i] -= vx[i] * dt; py[i] -= vy[i] * dt; pz[i] -= vz[i] * dt
                vx[i] = -vx[i] * 0.42f; vy[i] = -vy[i] * 0.42f; vz[i] = -vz[i] * 0.42f
            }
            rot[i] += spin[i] * dt
            // Ground collision: sparks bounce and skitter, smoke settles.
            val g = ground
            if (g != null) {
                val h = g(px[i], pz[i])
                if (py[i] < h) {
                    py[i] = h
                    if (bounce > 0f) {
                        vy[i] = -vy[i] * bounce
                        vx[i] *= 0.72f; vz[i] *= 0.72f
                        if (kotlin.math.abs(vy[i]) < 0.35f && bounce > 0.01f) { vy[i] = 0f; skitter(i) }
                    } else {
                        vy[i] = 0f
                        vx[i] *= 0.4f; vz[i] *= 0.4f
                    }
                }
            }
            i++
        }
    }

    /** Sparks die after skittering to rest instead of sliding forever. */
    private fun skitter(i: Int) {
        if (vx[i] * vx[i] + vz[i] * vz[i] < 0.2f && rng.chance(0.4f)) life[i] = maxLife[i]
    }

    private fun copy(from: Int, to: Int) {
        px[to] = px[from]; py[to] = py[from]; pz[to] = pz[from]
        vx[to] = vx[from]; vy[to] = vy[from]; vz[to] = vz[from]
        life[to] = life[from]; maxLife[to] = maxLife[from]
        size0[to] = size0[from]; size1[to] = size1[from]
        color0[to] = color0[from]; color1[to] = color1[from]
        rot[to] = rot[from]; spin[to] = spin[from]
        gravity[to] = gravity[from]; drag[to] = drag[from]
    }

    internal fun render(cam: Camera, renderer: Renderer3D) {
        if (count == 0) return
        val sh = shader
        if (!sh.bind()) return
        ensureBuffers()
        cam.right(right); cam.upVector(up)
        for (i in 0 until count) {
            val t = life[i] / maxLife[i]
            val o = i * FLOATS
            data[o] = px[i]; data[o + 1] = py[i]; data[o + 2] = pz[i]
            data[o + 3] = MathX.lerp(size0[i], size1[i], t)
            // The framebuffer holds display (sRGB) values, so particle colours stay sRGB.
            Colors.toSrgb(color0[i], lin0); Colors.toSrgb(color1[i], lin1)
            data[o + 4] = MathX.lerp(lin0[0], lin1[0], t); data[o + 5] = MathX.lerp(lin0[1], lin1[1], t)
            data[o + 6] = MathX.lerp(lin0[2], lin1[2], t); data[o + 7] = MathX.lerp(lin0[3], lin1[3], t)
            if (velocityAligned) {
                val sx = vx[i] * right.x + vy[i] * right.y + vz[i] * right.z
                val sy = vx[i] * up.x + vy[i] * up.y + vz[i] * up.z
                data[o + 8] = atan2(sy, sx) - MathX.HALF_PI
                data[o + 9] = 1f + sqrt(sx * sx + sy * sy) * 0.08f
            } else {
                data[o + 8] = rot[i]
                data[o + 9] = 1f
            }
        }
        val buf = buffer!!
        buf.position(0); buf.put(data, 0, count * FLOATS); buf.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, maxParticles * FLOATS * 4, null, GLES30.GL_STREAM_DRAW)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, count * FLOATS * 4, buf)
        sh.setMat4("u_viewProj", cam.viewProj.m)
        sh.set3f("u_camRight", right.x, right.y, right.z)
        sh.set3f("u_camUp", up.x, up.y, up.z)
        sh.set1i("u_tex", 0)
        sh.set1f("u_additive", if (additive) 1f else 0f)
        texture.bind(0)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, count)
        GLES30.glBindVertexArray(0)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_BLEND)
        renderer.stats.drawCalls++
        renderer.stats.instances += count
    }

    private fun ensureBuffers() {
        if (generation == Gpu.generation && vao != 0) return
        generation = Gpu.generation
        if (buffer == null) buffer = Gpu.floatBuffer(maxParticles * FLOATS)
        vao = Gpu.genVertexArray()
        cornerVbo = Gpu.genBuffer()
        instanceVbo = Gpu.genBuffer()
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, cornerVbo)
        val corners = Gpu.floatBuffer(8).put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); corners.position(0)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 32, corners, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, maxParticles * FLOATS * 4, null, GLES30.GL_STREAM_DRAW)
        val stride = FLOATS * 4
        GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, stride, 0); GLES30.glVertexAttribDivisor(1, 1)
        GLES30.glEnableVertexAttribArray(2); GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, stride, 16); GLES30.glVertexAttribDivisor(2, 1)
        GLES30.glEnableVertexAttribArray(3); GLES30.glVertexAttribPointer(3, 2, GLES30.GL_FLOAT, false, stride, 32); GLES30.glVertexAttribDivisor(3, 1)
        GLES30.glBindVertexArray(0)
    }

    companion object {
        private const val FLOATS = 10
        private val shader by lazy { Shader("particles", Shaders.PARTICLE_VERT, Shaders.PARTICLE_FRAG) }
    }
}
