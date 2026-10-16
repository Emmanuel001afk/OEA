package com.oea.engine.fx

import com.oea.engine.graphics.CharFx
import com.oea.engine.graphics.ParticleSystem
import com.oea.engine.graphics.Renderer3D
import com.oea.engine.graphics.Textures
import com.oea.engine.math.Colors
import com.oea.engine.math.Rng
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The VFX pipeline: organic, physics-driven effects for OEA games.
 *
 * - **Volumetric gas**: a [FluidGrid] carries smoke, fog and fire — it swirls,
 *   climbs, collides with terrain and dissipates. Particles ride the same air.
 * - **Real light**: fires, sparks and magical bursts push [com.oea.engine.graphics.PointLight]s
 *   into the environment, so their glow falls on skin, cloth and ground.
 * - **Context-aware particles**: sparks bounce and skitter off the terrain;
 *   dust puffs push the air around them; magic bursts displace the volume.
 * - **Environment ↔ character**: weather accumulates on every character's
 *   [CharFx] (wet rain-soaked cloth, dust settling, wind in hair and coats).
 *
 * One instance per game; call [update] before rendering and [register] with
 * each frame's renderer.
 */
class VfxSystem(
    /** Terrain/ground height query used for collisions and obstacles. */
    private val heightAt: (Float, Float) -> Float,
    /** World centre + size the fluid volume covers (metres). */
    centerX: Float = 0f,
    centerZ: Float = 0f,
    worldRadius: Float = 70f,
) {
    /** The gas volume. Public so games can push custom impulses. */
    val fluid = FluidGrid(centerX, centerZ, nx = 36, ny = 14, nz = 36, cell = max(1.4f, worldRadius / 22f))

    /** Soft smoke/fog puffs — alpha blended, carried by the fluid. */
    val smoke = ParticleSystem(900, Textures.softCircle(), additive = false)
    /** Hot bright motes: sparks, embers, magic — additive and velocity-stretched. */
    val sparks = ParticleSystem(700, Textures.softCircle(), additive = true, velocityAligned = true)
    /** Rain streaks / snow flakes / floating dust. */
    val weatherBits = ParticleSystem(700, Textures.softCircle(), additive = false, velocityAligned = true)

    private val rng = Rng(0xC0FFEE)
    private val windOut = FloatArray(3)

    // ------------------------------------------------------------ weather state

    /** 0..1 rain intensity. */
    var rain = 0f
    /** 0..1 snow. */
    var snow = 0f
    /** 0..1 ambient dust/sand. */
    var dust = 0f
    /** Global wind vector (m/s-ish); drives the fluid and hair/fabric. */
    var windX = 0f
    var windZ = 0f
    /** Ambient temperature shift: cold breath, heat shimmer tinting. */
    var cold = 0f

    /** Pooled dynamic lights handed to the environment each frame (no shot-time allocations). */
    private val lights = ArrayList<com.oea.engine.graphics.PointLight>(8)
    private val lightPool = Array(8) { com.oea.engine.graphics.PointLight() }
    private var lightCount = 0

    init {
        smoke.ground = heightAt
        smoke.bounce = 0f
        smoke.buoyancy = 1.1f
        sparks.ground = heightAt
        sparks.bounce = 0.52f
        val sample: (Float, Float, Float, FloatArray) -> Unit = { x, y, z, out -> fluid.sampleVelocity(x, y, z, out) }
        val solid: (Float, Float, Float) -> Boolean = { x, y, z -> fluid.isSolidAt(x, y, z) }
        smoke.wind = sample; smoke.obstacle = solid
        sparks.wind = sample; sparks.obstacle = solid
        weatherBits.ground = heightAt; weatherBits.obstacle = solid
        weatherBits.bounce = 0f
    }

    // ------------------------------------------------------------ emitters

    /** A living fire: hot gas, embers, glow and light. [strength] 0..1. */
    fun fire(x: Float, y: Float, z: Float, strength: Float = 1f) {
        val s = strength.coerceIn(0.1f, 1.4f)
        fluid.addSmoke(x, y, z, 0.28f * s, heat = 1.15f * s)
        fluid.addImpulse(x, y + 0.5f, z, rng.range(-0.4f, 0.4f), 1.6f * s, rng.range(-0.4f, 0.4f), radius = 1.2f)
        if (rng.chance(0.65f * s)) {
            sparks.emit(x + rng.range(-0.12f, 0.12f), y + 0.12f, z + rng.range(-0.12f, 0.12f),
                rng.range(-1.2f, 1.2f), rng.range(1.6f, 3.6f) * s, rng.range(-1.2f, 1.2f),
                rng.range(0.5f, 1.4f), 0.07f, 0.02f, 0xFFFFD89A.toInt(), 0x00FF6A20, gravity = -3.4f, drag = 0.8f)
        }
        if (rng.chance(0.5f * s)) {
            smoke.emit(x + rng.range(-0.2f, 0.2f), y + 0.35f, z + rng.range(-0.2f, 0.2f),
                rng.range(-0.25f, 0.25f), rng.range(0.6f, 1.3f), rng.range(-0.25f, 0.25f),
                rng.range(1.1f, 2.6f), 0.32f, 1.5f, 0x774A3A30.toInt(), 0x002A2420, gravity = 0.5f, drag = 0.9f, spin = 1.2f)
        }
        if (rng.chance(0.34f)) light(x, y + 0.45f, z, 0xFFFF9A48.toInt(), 1.5f * s, 6.5f * s)
    }

    /** A smoke source: a wreck, a torch, a caught bush. */
    fun smokeColumn(x: Float, y: Float, z: Float, rate: Float = 1f) {
        fluid.addSmoke(x, y, z, 0.32f * rate, heat = 0.55f)
        if (rng.chance(0.75f * rate)) {
            smoke.emit(x + rng.range(-0.3f, 0.3f), y + 0.2f, z + rng.range(-0.3f, 0.3f),
                rng.range(-0.2f, 0.2f), rng.range(0.7f, 1.5f), rng.range(-0.2f, 0.2f),
                rng.range(1.6f, 3.4f), 0.4f, 2.1f, 0x88504840.toInt(), 0x002E2A28, gravity = 0.65f, drag = 0.8f, spin = 1.4f)
        }
    }

    /**
     * Physics sparks: they bounce off terrain and skitter around objects —
     * a welded shower, an impact, a fuse.
     */
    fun sparksAt(x: Float, y: Float, z: Float, count: Int, dirX: Float = 0f, dirY: Float = 1f, dirZ: Float = 0f, hot: Int = 0xFFFFD89A.toInt()) {
        val n = (count * sparkDensity).toInt().coerceAtMost(count)
        for (k in 0 until n) {
            val dx = rng.range(-1f, 1f) + dirX * 1.4f
            val dy = rng.range(0.2f, 1f) + dirY * 1.4f
            val dz = rng.range(-1f, 1f) + dirZ * 1.4f
            val l = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.01f)
            val speed = rng.range(3.5f, 11f)
            sparks.emit(x, y, z, dx / l * speed, dy / l * speed, dz / l * speed,
                rng.range(0.35f, 1.1f), 0.075f, 0.015f, hot, 0x00FF6A00, gravity = -12f, drag = 0.35f)
        }
        if (count >= 6) light(x, y + 0.2f, z, 0xFFFFC27A.toInt(), 0.9f, 3.5f)
    }

    /**
     * A magical/energy burst: an outward shell of glowing motes, a pressure
     * impulse in the gas volume and a real light bloom that washes the
     * character and terrain around it.
     */
    fun magicBurst(x: Float, y: Float, z: Float, color: Int = 0xFF7AC8FF.toInt(), power: Float = 1f) {
        val n = (46 * power * sparkDensity).toInt().coerceAtMost(64)
        for (k in 0 until n) {
            var dx = rng.range(-1f, 1f); var dy = rng.range(-0.4f, 1f); var dz = rng.range(-1f, 1f)
            val l = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.01f)
            dx /= l; dy /= l; dz /= l
            val speed = rng.range(2.2f, 6.5f) * power
            sparks.emit(x, y, z, dx * speed, dy * speed + 0.8f, dz * speed,
                rng.range(0.5f, 1.2f), 0.12f, 0.02f, color, Colors.withAlpha(color, 0), drag = 1.4f)
        }
        fluid.addImpulse(x, y, z, 0f, 1.2f * power, 0f, radius = 3.2f * power)
        fluid.addSmoke(x, y, z, 0.22f * power, heat = 0.7f)
        light(x, y + 0.4f, z, color, 2.2f * power, 7.5f * power)
    }

    /** Dust kicked off the ground: footfalls, landings, a collapsing wall. */
    fun dustPuff(x: Float, y: Float, z: Float, amount: Float = 1f, dirX: Float = 0f, dirZ: Float = 0f) {
        val n = (7 * amount * sparkDensity).toInt().coerceAtMost(12)
        for (k in 0 until n) {
            smoke.emit(x + rng.range(-0.25f, 0.25f), y + 0.08f, z + rng.range(-0.25f, 0.25f),
                rng.range(-0.8f, 0.8f) + dirX, rng.range(0.3f, 1.1f) * amount + 0.3f, rng.range(-0.8f, 0.8f) + dirZ,
                rng.range(0.7f, 1.6f), 0.22f, 0.85f, 0x99C9B9A0.toInt(), 0x00C9B9A0, gravity = 0.35f, drag = 1.8f, spin = 1f)
        }
        fluid.addImpulse(x, y + 0.3f, z, dirX * 1.4f, 0.8f, dirZ * 1.4f, radius = 1.8f)
    }

    /** Everything at once: light flash, heat, bouncing sparks, a smoke ball. */
    fun explosion(x: Float, y: Float, z: Float, power: Float = 1f) {
        light(x, y + 0.6f, z, 0xFFFFB36A.toInt(), 4.2f * power, 12f * power)
        fluid.addImpulse(x, y + 0.4f, z, 0f, 3.2f * power, 0f, radius = 4.5f * power)
        fluid.addSmoke(x, y + 0.5f, z, 0.9f * power, heat = 1.8f)
        sparksAt(x, y + 0.4f, z, (26 * power).toInt(), dirY = 1.1f)
        val n = (10 * power * sparkDensity).toInt().coerceAtMost(16)
        for (k in 0 until n) {
            smoke.emit(x + rng.range(-0.5f, 0.5f), y + 0.6f + rng.range(0f, 1f), z + rng.range(-0.5f, 0.5f),
                rng.range(-2.4f, 2.4f), rng.range(1f, 3.2f), rng.range(-2.4f, 2.4f),
                rng.range(1.8f, 3.6f), 0.6f, 2.6f, 0x884A4038.toInt(), 0x00282220, gravity = 0.9f, drag = 1.2f, spin = 1.8f)
        }
    }

    /** A muzzle flash: tight sparks + light on the shooter's hands and face. */
    fun muzzle(x: Float, y: Float, z: Float, dirX: Float, dirY: Float, dirZ: Float, heavy: Float = 1f) {
        sparksAt(x, y, z, (5 * heavy).toInt(), dirX, dirY * 0.4f + 0.2f, dirZ, hot = 0xFFFFE6A0.toInt())
        light(x + dirX * 0.3f, y + 0.12f, z + dirZ * 0.3f, 0xFFFFD9A0.toInt(), 1.35f * heavy, 4.2f)
        fluid.addImpulse(x, y, z, dirX * 2.2f * heavy, 0.5f, dirZ * 2.2f * heavy, radius = 1.1f)
        if (heavy > 1.1f) smokeColumn(x + dirX * 0.5f, y + 0.15f, z + dirZ * 0.5f, 0.5f)
    }

    /** Fog bank: dense cool gas that hugs the ground. */
    fun fogBank(x: Float, y: Float, z: Float, radius: Float = 4f, density: Float = 0.5f) {
        for (k in 0 until 6) {
            val px = x + rng.range(-radius, radius)
            val pz = z + rng.range(-radius, radius)
            val py = y + rng.range(0f, 1.2f)
            fluid.addSmoke(px, py, pz, 0.35f * density, heat = -0.15f)
            // The fluid carries this visible volume; the puff is only its
            // adaptive representation at the current camera-scale density.
            smoke.emit(px, py, pz, rng.range(-0.3f, 0.3f), rng.range(-0.05f, 0.25f), rng.range(-0.3f, 0.3f),
                rng.range(2.2f, 4.5f), 0.7f * density, 2.2f * density, 0x665F7780.toInt(), 0x002A3840, gravity = -0.05f, drag = 0.6f, spin = 0.4f)
        }
    }

    /** Registers a dynamic light for this frame (glow that touches everything). */
    fun light(x: Float, y: Float, z: Float, color: Int, intensity: Float, radius: Float) {
        if (lightCount >= lightPool.size) return
        lights.add(lightPool[lightCount].set(x, y, z, color, intensity, radius))
        lightCount++
    }

    // ------------------------------------------------------------ update + draw

    /** Particle emission scales with quality; engine sets this each frame. */
    var sparkDensity = 1f

    /**
     * Steps the fluid and every particle system. Call once per frame with the
     * characters whose [CharFx] the weather should touch (or none).
     */
    fun update(dt: Float, characters: Collection<CharFx> = emptyList()) {
        val d = dt.coerceAtMost(0.1f)
        fluid.windX = windX
        fluid.windZ = windZ
        fluid.step(d)
        smoke.update(d)
        sparks.update(d)
        weatherBits.update(d)
        // Weather: rain/snow/dust fall through the same air and land on people.
        if (rain > 0.01f || snow > 0.01f || dust > 0.01f) updateWeather(d)
        // Environment ↔ character: wet, dusty, wind-blown.
        if (characters.isNotEmpty()) {
            for (fx in characters) fx.update(d, raining = rain, dusty = dust + snow * 0.4f, windX = windX, windZ = windZ)
        }
    }

    private fun updateWeather(dt: Float) {
        val cx = fluid.centerX; val cz = fluid.centerZ
        val r = 22f
        if (rain > 0.01f) {
            val n = (46f * rain * dt * 60f * sparkDensity).toInt()
            for (k in 0 until n) {
                val x = cx + rng.range(-r, r); val z = cz + rng.range(-r, r)
                val y = heightAt(x, z) + rng.range(4f, 14f)
                weatherBits.emit(x, y, z, windX * 0.7f, -13f, windZ * 0.7f, rng.range(0.35f, 0.6f),
                    0.045f, 0.03f, 0x99AAC4E0.toInt(), 0x55AAC4E0, drag = 0.2f)
            }
        }
        if (snow > 0.01f) {
            val n = (22f * snow * dt * 60f * sparkDensity).toInt()
            for (k in 0 until n) {
                val x = cx + rng.range(-r, r); val z = cz + rng.range(-r, r)
                val y = heightAt(x, z) + rng.range(3f, 11f)
                weatherBits.emit(x, y, z, windX * 1.1f + rng.range(-0.5f, 0.5f), rng.range(-1.3f, -0.8f), windZ * 1.1f + rng.range(-0.5f, 0.5f),
                    rng.range(1.6f, 3f), 0.09f, 0.05f, 0xCCEDF2F7.toInt(), 0x00EDF2F7, drag = 1.6f, spin = 2f)
            }
        }
        if (dust > 0.01f) {
            val n = (10f * dust * dt * 60f * sparkDensity).toInt()
            for (k in 0 until n) {
                val x = cx + rng.range(-r, r); val z = cz + rng.range(-r, r)
                val y = heightAt(x, z) + rng.range(0.2f, 3f)
                weatherBits.emit(x, y, z, windX * 1.6f + rng.range(-0.4f, 0.4f), rng.range(-0.15f, 0.35f), windZ * 1.6f + rng.range(-0.4f, 0.4f),
                    rng.range(1.2f, 2.6f), 0.12f, 0.3f, 0x77C9B9A0.toInt(), 0x00C9B9A0, drag = 1.2f, spin = 1f)
            }
            fluid.addImpulse(cx, 1.5f, cz, windX * 1.2f, 0f, windZ * 1.2f, radius = 10f)
        }
    }

    /**
     * Hands this frame's dynamic lights to the environment (cleared first),
     * then registers the particle systems with the renderer.
     */
    fun register(renderer: Renderer3D, env: com.oea.engine.graphics.Environment) {
        env.pointLights.clear()
        for (l in lights) env.pointLights.add(l)
        lights.clear()
        lightCount = 0
        renderer.particles(weatherBits)
        renderer.particles(smoke)
        renderer.particles(sparks)
    }

    /** Forgets every effect (match restart). */
    fun clear() {
        smoke.clear(); sparks.clear(); weatherBits.clear()
        lights.clear(); lightCount = 0
    }
}
