package com.oea.games.runner

import org.junit.Assert.*
import org.junit.Test

class RunnerTest {
    private val events = object : RunnerEvents {}
    private fun world(vararg obstacles: Obstacle): RunnerWorld = RunnerWorld(1).also {
        it.chunks.add(Chunk(0, 0f).apply { this.obstacles.addAll(obstacles) })
    }
    private fun advance(r: Runner, w: RunnerWorld, frames: Int) { repeat(frames) { r.step(w, 1f / 60f, events) } }

    @Test fun jumpClearsHurdleButNotTrain() {
        val hurdle = world(Obstacle(ObType.HURDLE, 1, 4.75f, 5.25f))
        val runner = Runner(0, "Runner", 0)
        runner.jump(events); advance(runner, hurdle, 40)
        assertFalse(runner.crashed)
        val train = world(Obstacle(ObType.TRAIN, 1, 5f, 20f))
        val other = Runner(0, "Runner", 0)
        other.jump(events); advance(other, train, 40)
        assertTrue(other.crashed)
    }

    @Test fun rampReachesRoofAndTrainFrontIsSolid() {
        val ramp = world(Obstacle(ObType.TRAIN, 1, 10f, 25f, ramp = true))
        val runner = Runner(0, "Runner", 0)
        advance(runner, ramp, 65)
        assertFalse(runner.crashed)
        assertEquals(RunnerWorld.TRAIN_TOP, runner.y, 0.1f)
        val front = world(Obstacle(ObType.TRAIN, 1, 5f, 20f))
        val other = Runner(0, "Runner", 0)
        advance(other, front, 40)
        assertTrue(other.crashed)
    }

    @Test fun rollClearsOverheadBarrier() {
        val w = world(Obstacle(ObType.OVERHEAD, 1, 4.7f, 5.3f))
        val r = Runner(0, "Runner", 0)
        r.roll(events); advance(r, w, 35)
        assertFalse(r.crashed)
        val standing = Runner(1, "Standing", 0)
        advance(standing, w, 35)
        assertTrue(standing.crashed)
    }

    @Test fun starterShieldIsCollectible() {
        val w = RunnerWorld(10).also { it.update(0f) }
        val r = Runner(0, "Runner", 0)
        advance(r, w, 300)
        assertFalse(r.crashed)
        assertTrue(r.has(PowerType.SHIELD))
    }

    @Test fun jumpCanBeBufferedBeforeLanding() {
        val r = Runner(0, "Runner", 0)
        val w = world()
        r.jump(events)
        advance(r, w, 43)
        r.jump(events)
        advance(r, w, 8)
        assertFalse(r.grounded)
        assertTrue(r.vy > 0f)
    }

    @Test fun coinFlowBuildsAndExpires() {
        val w = world()
        repeat(6) { w.chunks.first().coins.add(Coin(1, 1f + it * 1.5f, 0.9f)) }
        val r = Runner(0, "Runner", 0)
        advance(r, w, 65)
        assertEquals(6, r.coins)
        assertEquals(6, r.coinStreak)
        advance(r, w, 160)
        assertEquals(0, r.coinStreak)
    }
}
