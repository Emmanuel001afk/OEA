package com.oea.games.runner

import com.oea.engine.math.MathX
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/** Things that happened during a step, for sound, haptics and effects. */
internal interface RunnerEvents {
    fun onCoin(r: Runner, x: Float, y: Float, z: Float) {}
    fun onJump(r: Runner) {}
    fun onLand(r: Runner) {}
    fun onRoll(r: Runner) {}
    fun onStumble(r: Runner) {}
    fun onCrash(r: Runner) {}
    fun onShieldBreak(r: Runner) {}
    fun onPowerUp(r: Runner, type: PowerType) {}
}

/** One runner's simulation: lanes, jumping, rolling, roofs and ramps, collisions, coins and power-ups. */
internal class Runner(val id: Int, var name: String, var color: Int) {
    var z = 0f
    var x = RunnerWorld.laneX(1)
    var y = 0f
    var vy = 0f
    var lane = 1
    var prevLane = 1
    var speed = START_SPEED
    var rollTime = 0f
    var grounded = true
    var crashed = false
    var crashedAt = 0f
    var runTime = 0f
    var lastStumble = -100f
    var score = 0L
    var coins = 0
    private var scoreCarry = 0f
    val powers = FloatArray(PowerType.entries.size)
    var invulnerable = 0f
    /** Brief immunity to side bumps right after a stumble, while sliding back to the old lane. */
    private var sideGrace = 0f
    private var rollOnLand = false
    /** Animation state. */
    var phase = 0f
    var lean = 0f
    var stumbleFlash = 0f
    val skyCoins = ArrayList<FloatArray>()
    private var skyCoinTimer = 0f

    val distance: Float get() = z
    val multiplier: Int get() = if (powers[PowerType.MULTIPLIER.ordinal] > 0f) 2 else 1
    val rolling: Boolean get() = rollTime > 0f
    val height: Float get() = if (rolling) 0.9f else 1.8f
    fun has(p: PowerType) = powers[p.ordinal] > 0f

    fun reset() {
        z = 0f; x = RunnerWorld.laneX(1); y = 0f; vy = 0f; lane = 1; prevLane = 1; speed = START_SPEED
        rollTime = 0f; grounded = true; crashed = false; runTime = 0f; lastStumble = -100f; sideGrace = 0f
        score = 0L; coins = 0; scoreCarry = 0f; powers.fill(0f); invulnerable = 0f; rollOnLand = false
        phase = 0f; lean = 0f; skyCoins.clear(); stumbleFlash = 0f
    }

    // ---------------------------------------------------------------- controls

    fun moveLeft() { if (!crashed && lane > 0) { prevLane = lane; lane-- } }
    fun moveRight() { if (!crashed && lane < 2) { prevLane = lane; lane++ } }

    fun jump(events: RunnerEvents) {
        if (crashed || has(PowerType.JETPACK)) return
        if (grounded) {
            vy = if (has(PowerType.SNEAKERS)) 13.2f else 10.2f
            grounded = false
            rollTime = 0f
            events.onJump(this)
        }
    }

    fun roll(events: RunnerEvents) {
        if (crashed || has(PowerType.JETPACK)) return
        if (grounded) { rollTime = 0.72f; events.onRoll(this) }
        else { vy = -24f; rollOnLand = true }
    }

    // ---------------------------------------------------------------- simulation

    fun step(world: RunnerWorld, dt: Float, events: RunnerEvents) {
        if (crashed) return
        runTime += dt
        for (i in powers.indices) if (powers[i] > 0f) powers[i] = max(0f, powers[i] - dt)
        if (invulnerable > 0f) invulnerable -= dt
        if (sideGrace > 0f) sideGrace -= dt
        if (stumbleFlash > 0f) stumbleFlash -= dt
        speed = START_SPEED + (MAX_SPEED - START_SPEED) * (1f - exp(-z / 2200f))
        val prevZ = z
        z += speed * dt
        // Lateral movement towards the target lane.
        val tx = RunnerWorld.laneX(lane)
        val prevX = x
        x = MathX.approach(x, tx, LANE_SPEED * dt)
        lean = MathX.damp(lean, (x - prevX) / max(dt, 1e-4f) / LANE_SPEED, 18f, dt)
        val laneNow = nearestLane()
        if (has(PowerType.JETPACK)) {
            y = MathX.damp(y, JET_HEIGHT, 4f, dt)
            vy = 0f; grounded = false
            jetCoins(dt, events)
        } else {
            val ground = world.groundAt(laneNow, z)
            if (!grounded) {
                vy += GRAVITY * dt
                y += vy * dt
                if (y <= ground && vy <= 0f) {
                    y = ground; vy = 0f; grounded = true
                    events.onLand(this)
                    if (rollOnLand) { rollOnLand = false; rollTime = 0.72f; events.onRoll(this) }
                }
            } else {
                if (ground > y) y = ground                 // climbing a ramp
                else if (ground < y - 0.05f) { grounded = false; vy = 0f } // ran off a train
            }
        }
        if (rollTime > 0f) rollTime -= dt
        if (invulnerable <= 0f && !has(PowerType.JETPACK)) collide(world, prevZ, events)
        collect(world, dt, events)
        // Score: distance × multiplier.
        scoreCarry += speed * dt * multiplier
        val whole = scoreCarry.toLong()
        score += whole
        scoreCarry -= whole
        phase += dt * speed * 0.55f
    }

    fun nearestLane(): Int = when {
        x > RunnerWorld.LANE * 0.5f -> 0
        x < -RunnerWorld.LANE * 0.5f -> 2
        else -> 1
    }

    private fun collide(world: RunnerWorld, prevZ: Float, events: RunnerEvents) {
        val h = height
        world.forEachObstacle(z - 1.5f, z + 1.5f) { o ->
            if (crashed) return@forEachObstacle
            val ox = RunnerWorld.laneX(o.lane)
            val dx = abs(x - ox)
            if (dx > 1.1f + HALF_W) return@forEachObstacle
            if (z + HALF_D < o.z0 || z - HALF_D > o.z1) return@forEachObstacle
            if (o.type == ObType.TRAIN && y >= o.top - 0.45f) return@forEachObstacle // standing on the roof
            if (y >= o.top || y + h <= o.bottom) return@forEachObstacle            // above or below it
            val frontal = dx < 0.75f && (prevZ + HALF_D <= o.z0 + 0.05f + o.speed * 0.05f || z + HALF_D - o.z0 < 1.0f)
            if (frontal) hit(events) else if (sideGrace <= 0f) stumble(events)
        }
    }

    private fun hit(events: RunnerEvents) {
        if (has(PowerType.SHIELD)) {
            powers[PowerType.SHIELD.ordinal] = 0f
            invulnerable = 1.3f
            events.onShieldBreak(this)
            return
        }
        crashed = true
        crashedAt = runTime
        speed = 0f
        events.onCrash(this)
    }

    private fun stumble(events: RunnerEvents) {
        // Bounce back to the lane we came from; a second stumble soon after ends the run.
        val back = prevLane
        prevLane = lane
        lane = back
        stumbleFlash = 0.5f
        sideGrace = 0.45f
        if (runTime - lastStumble < 4f) { hit(events); return }
        lastStumble = runTime
        events.onStumble(this)
    }

    private fun collect(world: RunnerWorld, dt: Float, events: RunnerEvents) {
        val magnet = has(PowerType.MAGNET)
        for (c in world.chunks) {
            if (c.z1 < z - 3f || c.z0 > z + 12f) continue
            for (coin in c.coins) {
                if (coin.taken) continue
                val cx = RunnerWorld.laneX(coin.lane)
                var dz = coin.z - z
                if (dz < -2f || dz > 10f) continue
                var px = cx; var py = coin.y
                if (magnet && dz < 9f) {
                    coin.pull = minOf(1f, coin.pull + dt * 3.5f)
                    px = MathX.lerp(cx, x, coin.pull); py = MathX.lerp(coin.y, y + 1f, coin.pull); dz *= (1f - coin.pull)
                }
                val ddx = px - x; val ddy = py - (y + 0.9f)
                if (ddx * ddx + ddy * ddy + dz * dz < 1.3f) {
                    coin.taken = true
                    coins++
                    score += 10L * multiplier
                    events.onCoin(this, px, py, coin.z)
                }
            }
            for (p in c.powerUps) {
                if (p.taken) continue
                val dz = p.z - z
                if (dz < -1.5f || dz > 1.5f) continue
                val ddx = RunnerWorld.laneX(p.lane) - x; val ddy = p.y - (y + 0.9f)
                if (ddx * ddx + ddy * ddy + dz * dz < 2.2f) {
                    p.taken = true
                    powers[p.type.ordinal] = p.type.duration
                    if (p.type == PowerType.JETPACK) { grounded = false; rollTime = 0f }
                    events.onPowerUp(this, p.type)
                }
            }
        }
    }

    /** Coins in the sky while flying the jetpack. */
    private fun jetCoins(dt: Float, events: RunnerEvents) {
        skyCoinTimer -= dt
        if (skyCoinTimer <= 0f && powers[PowerType.JETPACK.ordinal] > 1.2f) {
            skyCoinTimer = 0.11f
            val laneSeed = ((z / 30f).toInt() * 7 + 3) % 3
            skyCoins += floatArrayOf(RunnerWorld.laneX(laneSeed), JET_HEIGHT + 0.9f, z + 40f)
        }
        val it = skyCoins.iterator()
        while (it.hasNext()) {
            val c = it.next()
            val dz = c[2] - z
            if (dz < -2f) { it.remove(); continue }
            val dx = c[0] - x; val dy = c[1] - (y + 0.9f)
            if (dz < 2f && sqrt(dx * dx + dy * dy + dz * dz) < 1.6f) {
                it.remove()
                coins++
                score += 10L * multiplier
                events.onCoin(this, c[0], c[1], c[2])
            }
        }
        if (powers[PowerType.JETPACK.ordinal] <= 0.05f) skyCoins.clear()
    }

    companion object {
        const val START_SPEED = 11f
        const val MAX_SPEED = 26f
        const val LANE_SPEED = 15f
        const val GRAVITY = -30f
        const val JET_HEIGHT = 7.5f
        const val HALF_W = 0.4f
        const val HALF_D = 0.35f
    }
}
