package com.oea.games.runner

import com.oea.engine.math.MathX
import com.oea.engine.math.Rng
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal enum class ObType { HURDLE, OVERHEAD, BLOCK, TRAIN }

internal enum class PowerType(val label: String, val glyph: String, val duration: Float, val color: Int) {
    MAGNET("Magnet", "⚡", 10f, 0xFFE53935.toInt()),
    JETPACK("Jetpack", "▲", 7f, 0xFF64B5F6.toInt()),
    SNEAKERS("Super jump", "↑", 10f, 0xFF66BB6A.toInt()),
    MULTIPLIER("2× score", "★", 12f, 0xFFCE93D8.toInt()),
    SHIELD("Shield", "●", 15f, 0xFF49C6FF.toInt()),
}

/** An obstacle on one lane. Trains span [z0, z1]; a ramp leads onto the roof when [ramp]. */
internal class Obstacle(val type: ObType, val lane: Int, var z0: Float, var z1: Float, val ramp: Boolean = false, val speed: Float = 0f, val style: Int = 0) {
    var active = false
    val top: Float get() = when (type) {
        ObType.HURDLE -> RunnerWorld.HURDLE_TOP
        ObType.OVERHEAD -> RunnerWorld.OVERHEAD_TOP
        ObType.BLOCK -> 3.2f
        ObType.TRAIN -> RunnerWorld.TRAIN_TOP
    }
    val bottom: Float get() = if (type == ObType.OVERHEAD) RunnerWorld.OVERHEAD_BOTTOM else 0f
    val rampStart: Float get() = z0 - RunnerWorld.RAMP_LENGTH
}

internal class Coin(val lane: Int, val z: Float, val y: Float) {
    var taken = false
    /** Magnet pull: 0..1 progress towards the player. */
    var pull = 0f
}

internal class PowerUp(val type: PowerType, val lane: Int, val z: Float, val y: Float) { var taken = false }

internal class Building(val side: Int, val z0: Float, val z1: Float, val height: Float, val depth: Float, val style: Int, val sign: Int)

internal class Chunk(val index: Int, val z0: Float) {
    val obstacles = ArrayList<Obstacle>()
    val coins = ArrayList<Coin>()
    val powerUps = ArrayList<PowerUp>()
    val buildings = ArrayList<Building>()
    val z1: Float get() = z0 + RunnerWorld.CHUNK
}

/**
 * Endless, seeded track. Every chunk is generated from (seed, chunk index)
 * alone, so all players of a race get the identical world and chunks can be
 * regenerated in any order. Patterns guarantee at least one passable lane.
 */
internal class RunnerWorld(var seed: Long) {
    val chunks = ArrayDeque<Chunk>()
    private var nextIndex = 0

    fun reset(newSeed: Long) {
        seed = newSeed
        chunks.clear()
        nextIndex = 0
    }

    /** Keeps chunks from just behind [z] to [ahead] metres ahead. */
    fun update(z: Float, ahead: Float = 210f) {
        while (chunks.isEmpty() || chunks.last().z1 < z + ahead) {
            chunks.addLast(generate(nextIndex++))
        }
        while (chunks.size > 2 && chunks.first().z1 < z - 40f) chunks.removeFirst()
    }

    /** Moving trains start rolling towards the runner once it is close. */
    fun updateMovers(playerZ: Float, dt: Float) {
        for (c in chunks) for (o in c.obstacles) {
            if (o.speed <= 0f) continue
            if (!o.active && o.z0 - playerZ < 75f) o.active = true
            if (o.active) { o.z0 -= o.speed * dt; o.z1 -= o.speed * dt }
        }
    }

    inline fun forEachObstacle(zMin: Float, zMax: Float, block: (Obstacle) -> Unit) {
        for (c in chunks) {
            if (c.z1 + 40f < zMin || c.z0 - 40f > zMax) continue
            for (o in c.obstacles) if (o.z1 >= zMin && o.rampStart <= zMax) block(o)
        }
    }

    /** Walkable height under (lane, z): 0, a ramp, or a train roof. */
    fun groundAt(lane: Int, z: Float): Float {
        var g = 0f
        forEachObstacle(z - 1f, z + 1f) { o ->
            if (o.lane != lane || o.type != ObType.TRAIN) return@forEachObstacle
            if (z >= o.z0 && z <= o.z1) g = max(g, TRAIN_TOP)
            else if (o.ramp && z >= o.rampStart && z < o.z0) g = max(g, TRAIN_TOP * (z - o.rampStart) / RAMP_LENGTH)
        }
        return g
    }

    // ---------------------------------------------------------------- generation

    private fun generate(index: Int): Chunk {
        val c = Chunk(index, index * CHUNK)
        val rng = Rng(seed xor (index.toLong() * -0x61c8864680b583ebL) xor 0x5EEDL)
        decorate(c, rng)
        if (index < 2) {
            // A calm start: coins only.
            coinLine(c, 1, c.z0 + 8f, 10, 2f)
            return c
        }
        val difficulty = MathX.clamp01(index / 70f)
        val pattern = rng.weighted(floatArrayOf(
            1.4f - difficulty,          // 0 coins & hurdles
            1.2f,                        // 1 hurdles
            1.0f,                        // 2 overheads
            1.2f,                        // 3 trains with a free lane
            1.0f + difficulty,           // 4 ramp train
            0.3f + difficulty * 1.6f,    // 5 moving train
            0.4f + difficulty * 1.2f,    // 6 zigzag blocks
            0.6f + difficulty,           // 7 mixed
            0.5f,                        // 8 roll tunnel
            0.2f + difficulty * 0.9f,    // 9 all trains, one ramp
        ))
        when (pattern) {
            0 -> { val l = rng.nextInt(3); coinLine(c, l, c.z0 + 4f, 12, 2f); if (rng.chance(0.6f)) hurdle(c, (l + 1 + rng.nextInt(2)) % 3, c.z0 + 15f) }
            1 -> {
                val z = c.z0 + 10f + rng.range(0f, 6f)
                val lanes = pick(rng, if (difficulty > 0.4f) 2 else 1)
                lanes.forEach { hurdle(c, it, z); coinArc(c, it, z) }
                if (rng.chance(0.5f)) { val l = (0..2).first { it !in lanes }; coinLine(c, l, c.z0 + 4f, 10, 2.2f) }
            }
            2 -> {
                val z = c.z0 + 12f
                val lanes = pick(rng, 1 + rng.nextInt(3))
                lanes.forEach { overhead(c, it, z); coinLine(c, it, z - 4f, 5, 2f, 0.5f) }
            }
            3 -> {
                val free = rng.nextInt(3)
                val len = rng.range(14f, 24f)
                (0..2).filter { it != free }.forEach { l -> if (rng.chance(0.75f)) train(c, l, c.z0 + 6f + rng.range(0f, 3f), len, ramp = false, rng) }
                coinLine(c, free, c.z0 + 4f, 12, 2f)
                if (rng.chance(0.4f)) hurdle(c, free, c.z0 + 18f)
            }
            4 -> {
                val l = rng.nextInt(3)
                val z = c.z0 + 10f
                val len = rng.range(16f, 26f)
                train(c, l, z, len, ramp = true, rng)
                // Coins up the ramp and along the roof.
                for (i in 0 until 4) c.coins += Coin(l, z - RAMP_LENGTH + 1f + i * 1.8f, TRAIN_TOP * (1f + i * 1.8f) / RAMP_LENGTH + 0.9f)
                coinLine(c, l, z + 1.5f, (len / 2.2f).toInt(), 2.2f, TRAIN_TOP + 0.9f)
                val other = (l + 1 + rng.nextInt(2)) % 3
                if (rng.chance(0.6f)) block(c, other, z + 4f)
                if (rng.chance(0.3f)) powerUp(c, rng, l, z + len - 3f, TRAIN_TOP + 1.2f)
            }
            5 -> {
                val l = rng.nextInt(3)
                train(c, l, c.z0 + 20f, rng.range(12f, 18f), ramp = false, rng, speed = rng.range(7f, 9f + difficulty * 6f))
                val safe = (l + 1 + rng.nextInt(2)) % 3
                coinLine(c, safe, c.z0 + 4f, 10, 2.2f)
                if (difficulty > 0.5f && rng.chance(0.5f)) {
                    val third = (0..2).first { it != l && it != safe }
                    train(c, third, c.z0 + 8f, rng.range(12f, 20f), ramp = false, rng)
                }
            }
            6 -> {
                val a = rng.nextInt(3)
                val b = (a + 1 + rng.nextInt(2)) % 3
                (0..2).filter { it != a }.forEach { block(c, it, c.z0 + 9f) }
                (0..2).filter { it != b }.forEach { block(c, it, c.z0 + 22f) }
                coinLine(c, a, c.z0 + 3f, 3, 2f)
                coinLine(c, b, c.z0 + 14f, 3, 2f)
            }
            7 -> {
                val order = mutableListOf(0, 1, 2).also { rng.shuffle(it) }
                val z = c.z0 + 13f
                hurdle(c, order[0], z); coinArc(c, order[0], z)
                overhead(c, order[1], z)
                if (difficulty > 0.3f) block(c, order[2], z) else coinLine(c, order[2], z - 6f, 6, 2f)
            }
            8 -> {
                val lanes = listOf(0, 1, 2)
                for (k in 0 until 3) lanes.forEach { overhead(c, it, c.z0 + 7f + k * 8f) }
                coinLine(c, rng.nextInt(3), c.z0 + 5f, 10, 2f, 0.5f)
            }
            else -> {
                val rampLane = rng.nextInt(3)
                val z = c.z0 + 10f
                (0..2).forEach { l -> train(c, l, z + (if (l == rampLane) 0f else rng.range(-2f, 3f)), rng.range(16f, 22f), ramp = l == rampLane, rng) }
                coinLine(c, rampLane, z + 1.5f, 7, 2.2f, TRAIN_TOP + 0.9f)
            }
        }
        if (rng.chance(0.16f + difficulty * 0.06f) && c.powerUps.isEmpty()) {
            val lane = freeLane(c, rng, c.z0 + 25f)
            if (lane >= 0) powerUp(c, rng, lane, c.z0 + 25f, 1.1f)
        }
        return c
    }

    private fun pick(rng: Rng, n: Int): List<Int> = mutableListOf(0, 1, 2).also { rng.shuffle(it) }.take(n.coerceIn(1, 3))

    private fun freeLane(c: Chunk, rng: Rng, z: Float): Int {
        val free = (0..2).filter { l -> c.obstacles.none { it.lane == l && z >= it.rampStart - 2f && z <= it.z1 + 2f } }
        return if (free.isEmpty()) -1 else free[rng.nextInt(free.size)]
    }

    private fun hurdle(c: Chunk, lane: Int, z: Float) { c.obstacles += Obstacle(ObType.HURDLE, lane, z - 0.25f, z + 0.25f) }
    private fun overhead(c: Chunk, lane: Int, z: Float) { c.obstacles += Obstacle(ObType.OVERHEAD, lane, z - 0.3f, z + 0.3f) }
    private fun block(c: Chunk, lane: Int, z: Float) { c.obstacles += Obstacle(ObType.BLOCK, lane, z - 0.6f, z + 0.6f) }
    private fun train(c: Chunk, lane: Int, z: Float, len: Float, ramp: Boolean, rng: Rng, speed: Float = 0f) {
        c.obstacles += Obstacle(ObType.TRAIN, lane, z, z + len, ramp, speed, rng.nextInt(RunnerArt.trainColors.size))
    }

    private fun coinLine(c: Chunk, lane: Int, z: Float, count: Int, spacing: Float, y: Float = 0.9f) {
        for (i in 0 until count) c.coins += Coin(lane, z + i * spacing, y)
    }

    /** Coins following the jump parabola over a hurdle at [z]. */
    private fun coinArc(c: Chunk, lane: Int, z: Float) {
        for (i in -3..3) {
            val dz = i * 1.6f
            val t = 1f - (dz / 5.5f) * (dz / 5.5f)
            c.coins += Coin(lane, z + dz, 0.9f + max(0f, t) * 1.5f)
        }
    }

    private fun powerUp(c: Chunk, rng: Rng, lane: Int, z: Float, y: Float) {
        val types = PowerType.entries
        c.powerUps += PowerUp(types[rng.nextInt(types.size)], lane, z, y)
    }

    private fun decorate(c: Chunk, rng: Rng) {
        for (side in intArrayOf(-1, 1)) {
            var z = c.z0
            while (z < c.z1 - 2f) {
                val len = rng.range(7f, 14f)
                val end = min(c.z1, z + len)
                c.buildings += Building(side, z, end - rng.range(0.5f, 1.5f), rng.range(7f, 30f), rng.range(6f, 12f), rng.nextInt(4),
                    if (rng.chance(0.25f)) rng.nextInt(3) else -1)
                z = end
            }
        }
    }

    companion object {
        const val CHUNK = 30f
        const val LANE = 2.4f
        const val HURDLE_TOP = 0.95f
        const val OVERHEAD_BOTTOM = 1.25f
        const val OVERHEAD_TOP = 2.4f
        const val TRAIN_TOP = 3.6f
        const val RAMP_LENGTH = 7f
        fun laneX(lane: Int) = (1 - lane) * LANE
        @Suppress("unused") fun laneOf(x: Float): Int = MathX.clamp(Math.round(1f - x / LANE), 0, 2).also { abs(it) }
    }
}
