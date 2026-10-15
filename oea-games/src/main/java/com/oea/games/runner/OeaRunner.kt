package com.oea.games.runner

import android.view.KeyEvent
import com.oea.engine.audio.MusicTrack
import com.oea.engine.audio.Sfx
import com.oea.engine.audio.Sound
import com.oea.engine.core.Choice
import com.oea.engine.core.GameDescriptor
import com.oea.engine.core.GameDimension
import com.oea.engine.core.GameOption
import com.oea.engine.core.GameOrientation
import com.oea.engine.core.GameResult
import com.oea.engine.core.MenuItem
import com.oea.engine.core.OeaGame
import com.oea.engine.core.PlayMode
import com.oea.engine.graphics.Align
import com.oea.engine.graphics.Environment
import com.oea.engine.graphics.Frame
import com.oea.engine.graphics.Mesh
import com.oea.engine.graphics.MeshBuilder
import com.oea.engine.graphics.ParticlePreset
import com.oea.engine.graphics.ParticleSystem
import com.oea.engine.graphics.PerspectiveCamera
import com.oea.engine.graphics.Renderer3D
import com.oea.engine.graphics.Textures
import com.oea.engine.graphics.CharRig
import com.oea.engine.graphics.Viewport
import com.oea.engine.graphics.ui
import com.oea.engine.graphics.world
import com.oea.engine.input.Swipe
import com.oea.engine.island.IslandStyle
import com.oea.engine.island.LiveActivity
import com.oea.engine.math.Colors
import com.oea.engine.math.Mat4
import com.oea.engine.math.MathX
import com.oea.engine.math.Vec3
import com.oea.engine.net.session.NetSession
import com.oea.engine.net.sync.SnapshotBuffer
import com.oea.engine.ui.ButtonStyle
import com.oea.engine.ui.Hud
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * OEA Runner: an endless train-yard runner. Swipe to change lanes, jump and
 * roll; ride ramps onto train roofs; grab coins and power-ups (magnet,
 * jetpack, super jump, 2× score, shield).
 *
 * Competition: split-screen duel on one phone (face to face), take turns on
 * the same seeded track, or race friends over Wi-Fi/hotspot or online where
 * everyone runs the identical track and sees the others as ghost runners.
 */
class OeaRunner : OeaGame() {
    private enum class Phase { COUNTDOWN, RUNNING, OVER }

    private inner class Lane(val runner: Runner, val world: RunnerWorld, val viewport: Viewport) {
        val camera = PerspectiveCamera(64f)
        val camPos = Vec3()
        val camTarget = Vec3()
        var shake = 0f
        val particles = ParticleSystem(1500, Textures.softCircle(64, 0.1f), additive = true)
        val dust = ParticleSystem(600, Textures.softCircle(64, 0f), additive = false)
    }

    private class Ghost(val id: Int, val name: String, val color: Int) {
        val buffer = SnapshotBuffer(4, delayMs = 110L)
        val state = FloatArray(4)  // x, y, z, flags
        var distance = 0f
        var score = 0L
        var coins = 0
        var crashed = false
        var visible = false
    }

    private val lanes = ArrayList<Lane>()
    private val ghosts = LinkedHashMap<Int, Ghost>()
    private var phase = Phase.COUNTDOWN
    private var countdownEnd = 0L
    private var lastCountdownBeep = -1
    private var mode = PlayMode.SOLO
    private var split = false
    private var turns = false
    private var turnIndex = 0
    private val turnScores = ArrayList<Pair<String, Long>>()
    private var raceGoal = 0f
    private var seed = 0L
    private var net: NetSession? = null
    private var sendTimer = 0f
    private var results: List<Triple<String, Long, Float>>? = null
    private var overAt = 0L
    private var paused = false
    private var newRecord = false
    private var hintUntil = 0L
    private val env = Environment.sunset().apply {
        fogDensity = 0.0105f
        shadowDistance = 34f
        sunIntensity = 2.3f
        ambientIntensity = 0.75f
    }
    private val m = Mat4()
    private val tmp = Vec3()

    private lateinit var sCoin: Sound
    private lateinit var sJump: Sound
    private lateinit var sLand: Sound
    private lateinit var sWhoosh: Sound
    private lateinit var sCrash: Sound
    private lateinit var sPower: Sound
    private lateinit var sCount: Sound
    private lateinit var sGo: Sound
    private lateinit var sHit: Sound

    private val sleeperMesh: Mesh by lazy { MeshBuilder().box(0f, 0.05f, 0f, 2.3f, 0.1f, 0.34f).build() }
    private val railMesh: Mesh by lazy { MeshBuilder().box(0f, 0.16f, 0f, 0.09f, 0.14f, 1f).build() }

    override fun onCreate() {
        val launch = ctx.launch
        mode = launch.mode
        net = ctx.net
        seed = net?.seed?.takeIf { it != 0L } ?: launch.seed
        split = mode == PlayMode.SAME_DEVICE && launch.option("duel", "split") == "split"
        turns = mode == PlayMode.SAME_DEVICE && !split
        raceGoal = when (net?.matchSettings?.get("race") ?: launch.option("race", "survival")) { "2k" -> 2000f; "5k" -> 5000f; else -> 0f }
        sCoin = ctx.audio.load("run-coin", Sfx.COIN)
        sJump = ctx.audio.load("run-jump", Sfx.JUMP)
        sLand = ctx.audio.load("run-land", Sfx.LAND)
        sWhoosh = ctx.audio.load("run-whoosh", Sfx.WHOOSH)
        sCrash = ctx.audio.load("run-crash", Sfx.CRASH)
        sPower = ctx.audio.load("run-power", Sfx.POWERUP)
        sCount = ctx.audio.load("run-count", Sfx.COUNTDOWN)
        sGo = ctx.audio.load("run-go", Sfx.GO)
        sHit = ctx.audio.load("run-hit", Sfx.HIT)
        ctx.audio.playMusic("runner", MusicTrack(bpm = 124, bars = 8, root = 55, minor = true, progression = listOf(0, 5, 3, 4), seed = 7))
        setupRunners()
        startCountdown(if (net != null) (net!!.startAt - net!!.now()).coerceIn(1500L, 6000L) else 3000L)
    }

    private fun setupRunners() {
        lanes.clear()
        ghosts.clear()
        pointerLane.clear()
        val players = ctx.launch.localPlayers
        val myColor = ctx.settings.playerColor
        if (split) {
            val p1 = players.getOrNull(0)
            val p2 = players.getOrNull(1)
            lanes += Lane(Runner(0, p1?.name ?: "Player 1", p1?.color ?: myColor), RunnerWorld(seed), Viewport.BOTTOM_HALF)
            lanes += Lane(Runner(1, p2?.name ?: "Player 2", p2?.color ?: 0xFFFF7043.toInt()), RunnerWorld(seed), Viewport(0f, 0f, 1f, 0.5f, rotate180 = true))
        } else {
            val name = if (turns) players.getOrNull(turnIndex)?.name ?: "Player ${turnIndex + 1}" else ctx.settings.playerName
            val color = if (turns) players.getOrNull(turnIndex)?.color ?: myColor else myColor
            lanes += Lane(Runner(net?.localId ?: 0, name, color), RunnerWorld(seed), Viewport.FULL)
            net?.players?.filter { it.id != net!!.localId }?.forEach { ghosts[it.id] = Ghost(it.id, it.name, it.color) }
        }
        lanes.forEach { l ->
            l.runner.reset()
            l.world.reset(seed)
            l.world.update(0f)
            l.camPos.set(0f, 4f, -7f)
            l.camTarget.set(0f, 1.2f, 6f)
            l.particles.density = ctx.graphics.quality.particleDensity
            l.dust.density = ctx.graphics.quality.particleDensity
        }
    }

    private fun startCountdown(ms: Long) {
        phase = Phase.COUNTDOWN
        countdownEnd = System.currentTimeMillis() + ms
        lastCountdownBeep = -1
        results = null
        hintUntil = System.currentTimeMillis() + ms + 3500L
    }

    // ---------------------------------------------------------------- events

    private val events = object : RunnerEvents {
        override fun onCoin(r: Runner, x: Float, y: Float, z: Float) {
            ctx.audio.play(sCoin, 0.55f, pitch = 1f + (r.coins % 8) * 0.03f)
            laneOf(r)?.particles?.burst(ParticlePreset.COIN_SPARKLE, x, y, z, 8)
        }
        override fun onJump(r: Runner) { ctx.audio.play(sJump, 0.6f) }
        override fun onLand(r: Runner) {
            ctx.audio.play(sLand, 0.5f)
            laneOf(r)?.dust?.burst(ParticlePreset.DUST, r.x, r.y + 0.05f, r.z, 10)
        }
        override fun onRoll(r: Runner) { ctx.audio.play(sWhoosh, 0.6f) }
        override fun onStumble(r: Runner) {
            ctx.audio.play(sHit, 0.8f); ctx.haptics.click()
            laneOf(r)?.let { it.shake = 0.35f }
        }
        override fun onShieldBreak(r: Runner) {
            ctx.audio.play(sHit); ctx.haptics.double()
            laneOf(r)?.particles?.burst(ParticlePreset.SPARKS, r.x, r.y + 1f, r.z + 0.5f, 40, tint = PowerType.SHIELD.color)
        }
        override fun onCrash(r: Runner) {
            ctx.audio.play(sCrash); ctx.haptics.heavy()
            laneOf(r)?.let { l ->
                l.shake = 0.8f
                l.particles.burst(ParticlePreset.SPARKS, r.x, r.y + 1f, r.z + 0.6f, 70)
                l.dust.burst(ParticlePreset.SMOKE, r.x, r.y + 0.8f, r.z + 0.5f, 18)
            }
            onRunnerCrashed(r)
        }
        override fun onPowerUp(r: Runner, type: PowerType) {
            ctx.audio.play(sPower); ctx.haptics.double()
            laneOf(r)?.particles?.burst(ParticlePreset.CONFETTI, r.x, r.y + 1.2f, r.z + 1f, 30, tint = type.color)
            if (lanes.size == 1) ctx.island.event("${type.label}!", type.glyph, 1800)
        }
    }

    private fun laneOf(r: Runner) = lanes.firstOrNull { it.runner === r }

    private fun onRunnerCrashed(r: Runner) {
        net?.broadcast(MSG_CRASH) { w -> w.f32(r.z).varlong(r.score).varint(r.coins) }
        if (lanes.all { it.runner.crashed }) endRun()
    }

    private fun endRun() {
        if (phase == Phase.OVER) return
        phase = Phase.OVER
        overAt = System.currentTimeMillis()
        val r = lanes[0].runner
        if (!split && net == null) newRecord = ctx.storage.submitBest("score", r.score)
        if (turns) {
            turnScores += r.name to r.score
        }
        ctx.storage.putLong("total_coins", ctx.storage.getLong("total_coins") + lanes.sumOf { it.runner.coins })
        if (net != null && net!!.isHost) maybeSendResults()
        ctx.island.event(if (split) winnerText() else "Run over · ${r.score} points", "🏃", 3500, important = true)
    }

    private fun winnerText(): String {
        val a = lanes[0].runner; val b = lanes.getOrNull(1)?.runner ?: return ""
        return when {
            a.score > b.score -> "${a.name} wins!"
            b.score > a.score -> "${b.name} wins!"
            else -> "It's a tie!"
        }
    }

    // ---------------------------------------------------------------- loop

    override fun onFixedUpdate(dt: Float) {
        pollNetwork()
        if (paused) return
        if (phase == Phase.COUNTDOWN) {
            val left = countdownEnd - System.currentTimeMillis()
            val sec = ((left + 999) / 1000).toInt()
            if (sec != lastCountdownBeep && sec in 1..3) { lastCountdownBeep = sec; ctx.audio.play(sCount) }
            if (left <= 0) { phase = Phase.RUNNING; ctx.audio.play(sGo); ctx.haptics.click() }
            return
        }
        for (l in lanes) {
            val r = l.runner
            if (!r.crashed) {
                l.world.update(r.z)
                l.world.updateMovers(r.z, dt)
                r.step(l.world, dt, events)
                if (raceGoal > 0f && r.z >= raceGoal && phase == Phase.RUNNING) finishRace(r)
            }
        }
        // Network: send our runner's state 15× per second.
        net?.let { s ->
            sendTimer -= dt
            if (sendTimer <= 0f && phase != Phase.OVER || sendTimer <= 0f && !lanes[0].runner.crashed) {
                sendTimer = 1f / 15f
                val r = lanes[0].runner
                val flags = (if (r.rolling) 1 else 0) or (if (!r.grounded) 2 else 0) or (if (r.crashed) 4 else 0) or (if (r.has(PowerType.JETPACK)) 8 else 0)
                s.broadcast(MSG_STATE, reliable = false) { w ->
                    w.i64(s.now()).f32(r.z).q16(r.x, -4f, 4f).q16(r.y, -1f, 12f).u8(flags).varlong(r.score).varint(r.coins)
                }
            }
        }
    }

    private fun finishRace(r: Runner) {
        net?.broadcast(MSG_FINISH) { w -> w.f32(r.z).varlong(r.score) }
        r.crashed = true
        endRun()
    }

    override fun onUpdate(dt: Float) {
        handleInput()
        for (l in lanes) {
            l.particles.update(dt)
            l.dust.update(dt)
            updateCamera(l, dt)
            val r = l.runner
            // Speed lines when going fast.
            if (phase == Phase.RUNNING && !r.crashed && r.speed > 18f && ctx.graphics.quality.particleDensity > 0.5f) {
                val k = (r.speed - 18f) / 8f
                if (Math.random() < k * 0.6f) {
                    val ang = Math.random().toFloat() * MathX.TAU
                    l.particles.emit(r.x + sin(ang) * 3.5f, r.y + 1.5f + kotlin.math.cos(ang) * 2.2f, r.z + 14f, 0f, 0f, -r.speed * 1.6f, 0.35f, 0.05f, 0.04f, 0x55FFFFFF, 0x00FFFFFF)
                }
            }
        }
        for (g in ghosts.values) g.visible = g.buffer.sample(net?.now() ?: 0L, g.state)
        publishIsland()
    }

    // ---------------------------------------------------------------- input

    /**
     * Split-screen touch ownership: each finger belongs to the player whose half
     * of the screen it went down in, for the whole stroke. Without this, a player
     * whose swipe chain drifts across the middle line would suddenly steer the
     * other runner — the classic same-screen input conflict.
     */
    private val pointerLane = HashMap<Int, Int>()

    private fun handleInput() {
        val input = ctx.input
        val h = ctx.graphics.height.toFloat()
        if (paused) return
        if (split) {
            // Bind new fingers to the half where they went down, and forget fingers
            // that came off the screen (pointer ids get recycled by the OS).
            for (p in input.pointers) {
                if (p.justPressed) pointerLane[p.id] = if (p.startY < h / 2f) 1 else 0
                if (!p.isDown && !p.justPressed) pointerLane.remove(p.id)
            }
        } else if (pointerLane.isNotEmpty()) pointerLane.clear()
        while (true) {
            val s = input.consumeSwipe() ?: break
            if (phase != Phase.RUNNING) continue
            // Split screen: the top half belongs to the player across the table
            // (rotated view). Ownership follows the finger, not just the chain start.
            val laneIdx = if (split) (pointerLane[s.pointerId] ?: if (s.startY < h / 2f) 1 else 0) else 0
            val l = lanes.getOrNull(laneIdx) ?: continue
            val rotated = l.viewport.rotate180
            val dir = if (!rotated) s.direction else when (s.direction) { Swipe.LEFT -> Swipe.RIGHT; Swipe.RIGHT -> Swipe.LEFT; Swipe.UP -> Swipe.DOWN; Swipe.DOWN -> Swipe.UP }
            apply(l.runner, dir)
        }
        if (phase == Phase.RUNNING) {
            val k = input.keys; val g = input.gamepad
            val r = lanes[0].runner
            if (k.justPressed(KeyEvent.KEYCODE_DPAD_LEFT) || k.justPressed(KeyEvent.KEYCODE_A) || g.justPressed(KeyEvent.KEYCODE_DPAD_LEFT)) apply(r, Swipe.LEFT)
            if (k.justPressed(KeyEvent.KEYCODE_DPAD_RIGHT) || k.justPressed(KeyEvent.KEYCODE_D) || g.justPressed(KeyEvent.KEYCODE_DPAD_RIGHT)) apply(r, Swipe.RIGHT)
            if (k.justPressed(KeyEvent.KEYCODE_DPAD_UP) || k.justPressed(KeyEvent.KEYCODE_W) || k.justPressed(KeyEvent.KEYCODE_SPACE) || g.justPressed(KeyEvent.KEYCODE_BUTTON_A)) apply(r, Swipe.UP)
            if (k.justPressed(KeyEvent.KEYCODE_DPAD_DOWN) || k.justPressed(KeyEvent.KEYCODE_S) || g.justPressed(KeyEvent.KEYCODE_BUTTON_B)) apply(r, Swipe.DOWN)
        }
    }

    private fun apply(r: Runner, dir: Swipe) {
        when (dir) {
            Swipe.LEFT -> r.moveLeft()
            Swipe.RIGHT -> r.moveRight()
            Swipe.UP -> r.jump(events)
            Swipe.DOWN -> r.roll(events)
        }
    }

    private fun updateCamera(l: Lane, dt: Float) {
        val r = l.runner
        val speedK = MathX.clamp01((r.speed - Runner.START_SPEED) / (Runner.MAX_SPEED - Runner.START_SPEED))
        val wantY = r.y * 0.75f + 3.3f + (if (r.has(PowerType.JETPACK)) 1.5f else 0f)
        tmp.set(r.x * 0.75f, wantY, r.z - 6.4f - speedK * 0.8f)
        l.camPos.damp(tmp, 9f, dt)
        tmp.set(r.x * 0.85f, r.y * 0.8f + 1.3f, r.z + 7f)
        l.camTarget.damp(tmp, 12f, dt)
        if (l.shake > 0f) l.shake = max(0f, l.shake - dt * 1.6f)
        val s = l.shake * l.shake
        val t = ctx.time.elapsed.toFloat()
        l.camera.position.set(l.camPos.x + sin(t * 47f) * 0.35f * s, l.camPos.y + sin(t * 61f) * 0.25f * s, l.camPos.z)
        l.camera.target.set(l.camTarget)
        l.camera.up.set(-r.lean * 0.04f, 1f, 0f).nor()
        l.camera.fovY = (62f + speedK * 12f) * MathX.DEG_TO_RAD
        l.camera.near = 0.3f
        l.camera.far = 230f
    }

    // ---------------------------------------------------------------- network

    private fun pollNetwork() {
        val s = net ?: return
        s.poll { msg ->
            val r = msg.reader()
            when (msg.type) {
                MSG_STATE -> {
                    val g = ghosts[msg.from] ?: return@poll
                    val time = r.i64()
                    val z = r.f32(); val x = r.q16(-4f, 4f); val y = r.q16(-1f, 12f); val flags = r.u8()
                    g.score = r.varlong(); g.coins = r.varint(); g.distance = z
                    g.crashed = flags and 4 != 0
                    g.buffer.push(time, floatArrayOf(x, y, z, flags.toFloat()))
                }
                MSG_CRASH, MSG_FINISH -> {
                    val g = ghosts[msg.from] ?: return@poll
                    g.distance = r.f32(); g.score = r.varlong(); g.crashed = true
                    if (msg.type == MSG_FINISH && s.isHost) { raceWinner = msg.from }
                    if (s.isHost) maybeSendResults()
                }
                MSG_RESULTS -> {
                    val n = r.u8()
                    results = (0 until n).map { Triple(r.string(64), r.varlong(), r.f32()) }
                    // A finished race ends everyone's run.
                    if (raceGoal > 0f && phase != Phase.OVER) { lanes[0].runner.crashed = true; endRun() }
                }
                MSG_RESTART -> {
                    seed = r.i64()
                    val delay = r.i32().toLong()
                    setupRunners()
                    startCountdown(delay)
                }
                NetSession.MSG_PLAYER_LEFT -> ghosts[msg.from]?.let { it.crashed = true; if (s.isHost) maybeSendResults() }
                NetSession.MSG_MATCH_ENDED -> ctx.ui.exit(GameResult("Race ended"))
            }
        }
    }

    private var raceWinner = -1

    /** Host: once everyone has crashed (or someone finished a distance race), publish the standings. */
    private fun maybeSendResults() {
        val s = net ?: return
        val me = lanes[0].runner
        val everyoneDone = me.crashed && ghosts.values.all { it.crashed }
        val distanceWon = raceGoal > 0f && (raceWinner >= 0 || me.z >= raceGoal)
        if (!everyoneDone && !distanceWon) return
        val list = (listOf(Triple(me.name, me.score, me.z)) + ghosts.values.map { Triple(it.name, it.score, it.distance) })
            .sortedWith(compareByDescending<Triple<String, Long, Float>> { if (raceGoal > 0f) it.third else it.second.toFloat() })
        results = list
        s.broadcast(MSG_RESULTS) { w ->
            w.u8(list.size)
            list.forEach { (n, sc, d) -> w.string(n).varlong(sc).f32(d) }
        }
    }

    private fun restart() {
        val s = net
        if (s != null) {
            if (!s.isHost) return
            seed = kotlin.random.Random.nextLong()
            s.broadcast(MSG_RESTART) { w -> w.i64(seed).i32(3500) }
            setupRunners()
            startCountdown(3500)
            return
        }
        if (turns && turnIndex == 0 && turnScores.size == 1) {
            turnIndex = 1
        } else if (turns) {
            turnIndex = 0
            turnScores.clear()
            seed = kotlin.random.Random.nextLong()
        } else {
            seed = kotlin.random.Random.nextLong()
        }
        setupRunners()
        startCountdown(3000)
    }

    private fun publishIsland() {
        val r = lanes[0].runner
        if (phase == Phase.OVER) { ctx.island.end(); return }
        val rank = if (ghosts.isNotEmpty()) 1 + ghosts.values.count { it.distance > r.z } else 0
        ctx.island.update(LiveActivity(
            title = if (net != null) "Runner race" else if (split) "Runner duel" else "Runner",
            subtitle = "${r.score} pts · ${r.coins} coins · ${r.z.toInt()} m",
            compactText = if (rank > 0) "Place $rank of ${ghosts.size + 1}" else "${r.z.toInt()} m",
            trailing = if (rank > 0) "#$rank" else "${r.score}",
            progress = if (raceGoal > 0f) r.z / raceGoal else -1f,
            preferred = IslandStyle.CAMERA,
        ))
    }

    override fun keepsRunningInBackground(): Boolean = net != null

    override fun onPause() { if (net == null && phase == Phase.RUNNING) paused = true }

    override fun onBack(): Boolean {
        if (net == null && phase == Phase.RUNNING) { paused = !paused; if (paused) openMenu(); return true }
        openMenu()
        return true
    }

    private fun openMenu() {
        val items = ArrayList<MenuItem>()
        if (paused) items += MenuItem("resume", "Resume")
        if (net == null) items += MenuItem("restart", "Restart run")
        items += MenuItem("exit", if (net != null) "Leave race" else "Exit", destructive = true)
        ctx.ui.menu("Runner", if (paused) "Paused" else null, items) { id ->
            when (id) {
                "resume", null -> paused = false
                "restart" -> { paused = false; restart() }
                "exit" -> ctx.ui.exit(GameResult("Run ended", lanes[0].runner.score))
            }
        }
    }

    override fun onDestroy() { ctx.audio.stopMusic() }

    // ---------------------------------------------------------------- rendering

    override fun onRender(frame: Frame) {
        frame.clear(0xFF101318.toInt())
        for (l in lanes) {
            frame.world(l.camera, l.viewport, env) { r -> drawWorld(r, l) }
        }
        frame.ui { hud -> drawHud(hud) }
    }

    private fun drawWorld(r: Renderer3D, l: Lane) {
        val runner = l.runner
        val world = l.world
        val t = ctx.time.elapsed.toFloat()
        val detail = ctx.graphics.quality.detailDistance
        for (c in world.chunks) {
            if (c.z0 > runner.z + 200f * detail) continue
            val zc = (c.z0 + c.z1) / 2f
            // Ground, gravel bed and the three tracks.
            r.draw(Mesh.unitCube, RunnerArt.ground, 0f, -0.55f, zc, 0f, 0f, 0f, 60f, 1f, RunnerWorld.CHUNK)
            r.draw(Mesh.unitCube, RunnerArt.gravel, 0f, -0.04f, zc, 0f, 0f, 0f, 8.4f, 0.1f, RunnerWorld.CHUNK)
            for (lane in 0..2) {
                val lx = RunnerWorld.laneX(lane)
                r.draw(railMesh, RunnerArt.rail, lx - 0.72f, 0f, zc, 0f, 0f, 0f, 1f, 1f, RunnerWorld.CHUNK)
                r.draw(railMesh, RunnerArt.rail, lx + 0.72f, 0f, zc, 0f, 0f, 0f, 1f, 1f, RunnerWorld.CHUNK)
                var z = c.z0 + 0.75f
                while (z < c.z1) { r.draw(sleeperMesh, RunnerArt.sleeper, lx, 0f, z); z += 1.5f }
            }
            // Platforms and lamps.
            for (side in intArrayOf(-1, 1)) {
                RunnerArt.boxAt(r, RunnerArt.concrete, side * 4.4f - 0.2f, -0.2f, c.z0, side * 4.4f + 0.2f, 0.35f, c.z1)
                var z = c.z0 + 5f
                while (z < c.z1) {
                    r.draw(RunnerArt.lampPost, RunnerArt.metalDark, side * 5.4f, 0f, z, if (side > 0) MathX.PI_F else 0f, 1f)
                    r.draw(Mesh.unitSphere, RunnerArt.lampGlow, side * 5.4f - side * 0.9f, 5.05f, z, 0f, 0.28f)
                    z += 15f
                }
            }
            for (b in c.buildings) {
                val x0 = b.side * 7.5f
                val x1 = b.side * (7.5f + b.depth)
                val mat = RunnerArt.buildingMats[b.style]
                RunnerArt.boxAt(r, mat, min(x0, x1), 0f, b.z0, max(x0, x1), b.height, b.z1)
                RunnerArt.boxAt(r, RunnerArt.roofTop, min(x0, x1) - 0.2f, b.height, b.z0 - 0.2f, max(x0, x1) + 0.2f, b.height + 0.5f, b.z1 + 0.2f)
                if (b.sign >= 0) RunnerArt.boxAt(r, RunnerArt.neonSigns[b.sign], x0 - b.side * 0.05f, b.height * 0.55f, b.z0 + 1f, x0 + b.side * 0.15f, b.height * 0.55f + 1.4f, b.z1 - 1f)
            }
            for (o in c.obstacles) drawObstacle(r, o)
            for (coin in c.coins) {
                if (coin.taken) continue
                val dz = coin.z - runner.z
                if (dz < -3f || dz > 120f * detail) continue
                var x = RunnerWorld.laneX(coin.lane); var y = coin.y; var z = coin.z
                if (coin.pull > 0f) { x = MathX.lerp(x, runner.x, coin.pull); y = MathX.lerp(y, runner.y + 1f, coin.pull); z = MathX.lerp(z, runner.z, coin.pull) }
                r.draw(RunnerArt.coinMesh, RunnerArt.coin, x, y, z, t * 4f + coin.z * 0.3f, 1f)
            }
            for (p in c.powerUps) {
                if (p.taken) continue
                val bob = sin(t * 3f + p.z) * 0.15f
                drawPowerUp(r, p.type, RunnerWorld.laneX(p.lane), p.y + bob, p.z, t * 2f)
            }
        }
        for (sc in runner.skyCoins) r.draw(RunnerArt.coinMesh, RunnerArt.coin, sc[0], sc[1], sc[2], t * 4f + sc[2] * 0.3f, 1f)
        drawCharacter(r, runner, ghost = false)
        if (!r.shadowMapsActive && !runner.has(PowerType.JETPACK)) r.blobShadow(runner.x, world.groundAt(runner.nearestLane(), runner.z), runner.z, 0.55f, 0.45f)
        // Other runners as ghosts in this view.
        for (other in lanes) if (other !== l) drawGhostRunner(r, other.runner.x, other.runner.y, other.runner.z, other.runner.color, other.runner.rolling, other.runner.phase)
        for (g in ghosts.values) if (g.visible) {
            val flags = g.state[3].toInt()
            drawGhostRunner(r, g.state[0], g.state[1], g.state[2], g.color, flags and 1 != 0, g.state[2] * 0.55f)
        }
        r.particles(l.dust)
        r.particles(l.particles)
    }

    private fun drawObstacle(r: Renderer3D, o: Obstacle) {
        val x = RunnerWorld.laneX(o.lane)
        when (o.type) {
            ObType.HURDLE -> {
                RunnerArt.boxAt(r, RunnerArt.metalDark, x - 0.95f, 0f, o.z0 + 0.15f, x - 0.85f, 0.9f, o.z1 - 0.15f)
                RunnerArt.boxAt(r, RunnerArt.metalDark, x + 0.85f, 0f, o.z0 + 0.15f, x + 0.95f, 0.9f, o.z1 - 0.15f)
                for (k in 0 until 4) RunnerArt.boxAt(r, if (k % 2 == 0) RunnerArt.hurdleRed else RunnerArt.hurdleWhite, x - 1f + k * 0.5f, 0.6f, o.z0 + 0.1f, x - 0.5f + k * 0.5f, 0.95f, o.z1 - 0.1f)
            }
            ObType.OVERHEAD -> {
                RunnerArt.boxAt(r, RunnerArt.metalDark, x - 1.15f, 0f, o.z0 + 0.1f, x - 1.0f, RunnerWorld.OVERHEAD_TOP, o.z1 - 0.1f)
                RunnerArt.boxAt(r, RunnerArt.metalDark, x + 1.0f, 0f, o.z0 + 0.1f, x + 1.15f, RunnerWorld.OVERHEAD_TOP, o.z1 - 0.1f)
                for (k in 0 until 4) RunnerArt.boxAt(r, if (k % 2 == 0) RunnerArt.warning else RunnerArt.metalDark, x - 1.1f + k * 0.55f, RunnerWorld.OVERHEAD_BOTTOM, o.z0, x - 0.55f + k * 0.55f, RunnerWorld.OVERHEAD_TOP, o.z1)
            }
            ObType.BLOCK -> {
                RunnerArt.boxAt(r, RunnerArt.concrete, x - 1.05f, 0f, o.z0, x + 1.05f, 3.2f, o.z1)
                RunnerArt.boxAt(r, RunnerArt.warning, x - 1.06f, 2.6f, o.z0 - 0.01f, x + 1.06f, 2.9f, o.z1 + 0.01f)
            }
            ObType.TRAIN -> {
                val body = RunnerArt.trainColors[o.style]
                RunnerArt.boxAt(r, body, x - 1.12f, 0.3f, o.z0, x + 1.12f, RunnerWorld.TRAIN_TOP - 0.2f, o.z1)
                RunnerArt.boxAt(r, RunnerArt.trainRoof, x - 1.0f, RunnerWorld.TRAIN_TOP - 0.2f, o.z0 + 0.2f, x + 1.0f, RunnerWorld.TRAIN_TOP, o.z1 - 0.2f)
                RunnerArt.boxAt(r, RunnerArt.trainWindow, x - 1.13f, 1.7f, o.z0 + 0.8f, x + 1.13f, 2.6f, o.z1 - 0.8f)
                RunnerArt.boxAt(r, RunnerArt.metalDark, x - 1.0f, 0f, o.z0 + 0.5f, x + 1.0f, 0.32f, o.z1 - 0.5f)
                // Front face with lights (brighter on moving trains).
                RunnerArt.boxAt(r, RunnerArt.trainWindow, x - 0.8f, 1.8f, o.z0 - 0.02f, x + 0.8f, 2.8f, o.z0)
                val lightMat = RunnerArt.trainLight
                r.draw(Mesh.unitSphere, lightMat, x - 0.75f, 0.9f, o.z0 - 0.05f, 0f, if (o.speed > 0f) 0.32f else 0.2f)
                r.draw(Mesh.unitSphere, lightMat, x + 0.75f, 0.9f, o.z0 - 0.05f, 0f, if (o.speed > 0f) 0.32f else 0.2f)
                if (o.ramp) {
                    // Ramp: a tilted slab from the ground up to the roof.
                    val len = RunnerWorld.RAMP_LENGTH
                    val ang = kotlin.math.atan2(RunnerWorld.TRAIN_TOP, len)
                    val hyp = kotlin.math.sqrt(len * len + RunnerWorld.TRAIN_TOP * RunnerWorld.TRAIN_TOP)
                    m.setTRS(x, RunnerWorld.TRAIN_TOP / 2f - 0.08f, o.z0 - len / 2f, 0f, ang, 0f, 2.0f, 0.16f, hyp)
                    r.draw(Mesh.unitCube, RunnerArt.metalDark, m)
                    for (k in 0 until 6) {
                        val f = (k + 0.5f) / 6f
                        RunnerArt.boxAt(r, RunnerArt.warning, x - 0.95f, RunnerWorld.TRAIN_TOP * f - 0.02f, o.z0 - len + len * f - 0.12f, x + 0.95f, RunnerWorld.TRAIN_TOP * f + 0.04f, o.z0 - len + len * f + 0.12f)
                    }
                }
            }
        }
    }

    private fun drawPowerUp(r: Renderer3D, type: PowerType, x: Float, y: Float, z: Float, spin: Float) {
        when (type) {
            PowerType.MAGNET -> r.draw(RunnerArt.magnet, RunnerArt.magnetRed, x, y, z, spin, 1.2f)
            PowerType.JETPACK -> r.draw(RunnerArt.jetpack, RunnerArt.jetpackMat, x, y, z, spin, 1.1f)
            PowerType.SNEAKERS -> r.draw(RunnerArt.sneaker, RunnerArt.sneakerMat, x, y, z, spin, 1.1f)
            PowerType.MULTIPLIER -> r.draw(RunnerArt.star, RunnerArt.multiplierMat, x, y, z, spin, 1.2f)
            PowerType.SHIELD -> r.draw(RunnerArt.bubble, RunnerArt.shieldMat, x, y, z, spin, 1.1f)
        }
    }

    /** One rig per runner colour (materials are cached per colour). */
    private val charRigs = HashMap<Int, CharRig>()
    private val ghostRigs = HashMap<Int, CharRig>()
    private val charPose = CharRig.Pose()

    private fun rigFor(color: Int, ghost: Boolean): CharRig =
        if (ghost) ghostRigs.getOrPut(color) {
            val m = RunnerArt.ghostMaterial(color)
            CharRig(m, m, m, m, m, ghost = true, accent = m)
        } else charRigs.getOrPut(color) {
            CharRig(
                body = RunnerArt.bodyMaterial(color),
                pants = com.oea.engine.graphics.Material(color = com.oea.engine.math.Colors.lerp(color, 0xFF10141A.toInt(), 0.45f), specular = 0.45f, shininess = 40f),
                skin = com.oea.engine.graphics.Material(color = 0xFFE8B78C.toInt(), specular = 0.35f, shininess = 30f, rim = 0.25f),
                shoes = com.oea.engine.graphics.Material(color = 0xFFF5F5F5.toInt(), specular = 0.35f),
                hair = com.oea.engine.graphics.Material(color = 0xFF3A2418.toInt(), specular = 0.4f, shininess = 48f),
                accent = com.oea.engine.graphics.Material(color = com.oea.engine.math.Colors.lerp(color, 0xFF10141A.toInt(), 0.25f), specular = 0.4f),
            )
        }

    /** A real animated character on the shared rig, not a stack of boxes. */
    private fun drawCharacter(r: Renderer3D, run: Runner, ghost: Boolean) {
        val body = if (ghost) RunnerArt.ghostMaterial(run.color) else RunnerArt.bodyMaterial(run.color)
        val x = run.x; val y = run.y; val z = run.z
        val roll = run.rolling
        if (run.crashed) {
            // Knocked over.
            r.draw(RunnerArt.torso, body, x, y + 0.35f, z, 0f, 1.4f, 0.2f, 1f, 1f, 1f)
            r.draw(RunnerArt.head, if (ghost) body else RunnerArt.skinDark, x, y + 0.4f, z - 0.75f)
            return
        }
        if (roll) {
            // Tucked into a ball, spinning.
            val spin = run.phase * 2.2f
            r.draw(Mesh.unitSphere, body, x, y + 0.48f, z, 0f, spin, 0f, 0.95f, 0.95f, 0.95f)
            r.draw(RunnerArt.head, if (ghost) body else RunnerArt.skinDark, x, y + 0.62f + sin(spin) * 0.25f, z + kotlin.math.cos(spin) * 0.25f, 0f, 0.8f)
            return
        }
        val speedK = MathX.clamp01((run.speed - Runner.START_SPEED) / (Runner.MAX_SPEED - Runner.START_SPEED))
        charPose.phase = run.phase * 1.05f
        charPose.speed = if (run.grounded) 0.45f + speedK * 0.55f else 0f
        charPose.airborne = !run.grounded
        charPose.crouch = false
        charPose.lean = run.lean
        charPose.aimHold = false
        charPose.detail = true
        val hipY = y + 0.92f
        rigFor(run.color, ghost).draw(r, x, y, z, 0f, charPose)
        // Glowing sport visor across the face.
        if (!ghost) r.draw(Mesh.unitSphere, RunnerArt.visor, x, hipY + 0.62f, z + 0.12f, 0f, 0f, 0f, 0.30f, 0.10f, 0.16f)
        if (run.has(PowerType.JETPACK)) {
            r.draw(RunnerArt.jetpack, RunnerArt.jetpackMat, x, hipY + 0.5f, z - 0.32f, 0f, 0.8f)
            laneOf(run)?.particles?.let { p ->
                p.emit(x - 0.14f, hipY + 0.1f, z - 0.35f, 0f, -6f, -run.speed * 0.3f, 0.3f, 0.22f, 0.02f, 0xFFFFC266.toInt(), 0x00FF5000)
                p.emit(x + 0.14f, hipY + 0.1f, z - 0.35f, 0f, -6f, -run.speed * 0.3f, 0.3f, 0.22f, 0.02f, 0xFFFFC266.toInt(), 0x00FF5000)
            }
        }
        if (run.has(PowerType.SHIELD) || run.invulnerable > 0f) {
            val pulse = 1f + sin(ctx.time.elapsed.toFloat() * 6f) * 0.04f
            r.draw(RunnerArt.bubble, RunnerArt.shieldMat, x, hipY + 0.2f, z, 0f, 2.3f * pulse)
        }
        if (run.has(PowerType.MAGNET)) r.draw(RunnerArt.magnet, RunnerArt.magnetRed, x + 0.45f, hipY + 0.55f, z + 0.15f, 0f, 0.5f)
    }

    private fun drawGhostRunner(r: Renderer3D, x: Float, y: Float, z: Float, color: Int, rolling: Boolean, phase: Float) {
        val mat = RunnerArt.ghostMaterial(color)
        if (rolling) { r.draw(Mesh.unitSphere, mat, x, y + 0.48f, z, 0f, 0.95f); return }
        charPose.phase = phase * 1.05f
        charPose.speed = 0.7f
        charPose.airborne = false
        charPose.crouch = false
        charPose.lean = 0f
        charPose.aimHold = false
        charPose.detail = true
        rigFor(color, ghost = true).draw(r, x, y, z, 0f, charPose)
    }

    // ---------------------------------------------------------------- HUD

    private fun drawHud(hud: Hud) {
        hud.accent = ACCENT
        if (split) {
            // Each player's HUD in their half; the top one is upside down.
            drawLaneHud(hud, lanes[0], 0f, hud.height / 2f, hud.height / 2f)
            hud.batch.end()
            val rot = Mat4().ortho(0f, hud.width, hud.height, 0f, -1f, 1f)
                .mul(Mat4().setTRS(hud.width / 2f, hud.height / 2f, 0f, roll = MathX.PI_F)).mul(Mat4().setTranslation(-hud.width / 2f, -hud.height / 2f, 0f))
            hud.batch.begin(rot)
            drawLaneHud(hud, lanes[1], 0f, hud.height / 2f, hud.height / 2f, rotated = true)
            hud.batch.end()
            hud.batch.beginPixels(hud.width, hud.height)
            hud.batch.rect(0f, hud.height / 2f - hud.dp(1.5f), hud.width, hud.dp(3f), 0xCC000000.toInt())
        } else {
            drawLaneHud(hud, lanes[0], 0f, 0f, hud.height)
            if (ghosts.isNotEmpty()) drawStandings(hud)
        }
        drawCountdown(hud)
        if (phase == Phase.OVER) drawResults(hud)
        if (net == null && phase != Phase.OVER && !split) {
            if (hud.iconButton("pause", "❚❚", hud.width - hud.safeRight - hud.dp(34f), hud.height - hud.safeBottom - hud.dp(40f), 20f)) {
                paused = true; openMenu()
            }
        }
    }

    private fun drawLaneHud(hud: Hud, l: Lane, x: Float, y: Float, h: Float, rotated: Boolean = false) {
        val r = l.runner
        val d = hud.density
        val top = y + (if (rotated || !split) hud.safeTop else 0f) + 10f * d
        val left = x + hud.safeLeft + 14f * d
        val right = hud.width - hud.safeRight - 14f * d
        hud.text("${r.score}", left, top, 30f, Colors.WHITE, bold = true)
        if (r.multiplier > 1) {
            hud.batch.roundRect(left, top + 40f * d, 46f * d, 24f * d, 12f * d, PowerType.MULTIPLIER.color)
            hud.text("×${r.multiplier}", left + 23f * d, top + 43f * d, 14f, Colors.WHITE, Align.CENTER, bold = true, shadow = false)
        }
        if (split) hud.text(r.name, left, top + (if (r.multiplier > 1) 70f else 42f) * d, 14f, r.color, bold = true)
        // Coins and distance on the right.
        hud.batch.circle(right - 12f * d, top + 16f * d, 11f * d, 0xFFFFC83D.toInt())
        hud.batch.circle(right - 12f * d, top + 16f * d, 6f * d, 0xFFFFE08A.toInt())
        hud.text("${r.coins}", right - 30f * d, top + 4f * d, 22f, Colors.WHITE, Align.RIGHT, bold = true)
        hud.text("${r.z.toInt()} m", right, top + 34f * d, 14f, 0xCCFFFFFF.toInt(), Align.RIGHT)
        if (raceGoal > 0f) {
            val bw = right - left
            hud.bar(left, y + h - hud.safeBottom * (if (split && !rotated) 1f else 0f) - 24f * d, bw, 6f * d, r.z / raceGoal, ACCENT)
        }
        // Power-up timers.
        var py = top + 70f * d
        for (p in PowerType.entries) {
            val left2 = r.powers[p.ordinal]
            if (left2 <= 0f) continue
            val cx = right - 22f * d; val cy = py + 22f * d
            hud.batch.circle(cx, cy, 20f * d, 0xAA101418.toInt())
            hud.batch.arc(cx, cy, 20f * d, 4f * d, 0f, MathX.TAU * (left2 / p.duration), p.color)
            hud.text(p.glyph, cx, cy - 10f * d, 16f, Colors.WHITE, Align.CENTER, bold = true, shadow = false)
            py += 50f * d
        }
        if (r.stumbleFlash > 0f) hud.batch.rect(x, y, hud.width, h, Colors.withAlpha(0xFFFF3B30.toInt(), r.stumbleFlash * 0.35f))
        if (System.currentTimeMillis() < hintUntil && phase != Phase.OVER) {
            hud.text("Swipe ← → to switch lanes · ↑ jump · ↓ roll", hud.width / 2f, y + h - (if (!rotated && !split) hud.safeBottom else 0f) - 90f * d, 14f, 0xDDFFFFFF.toInt(), Align.CENTER)
        }
        if (r.crashed && phase != Phase.OVER && split) {
            hud.text("Crashed!", hud.width / 2f, y + h / 2f - 20f * d, 28f, 0xFFFF6B6B.toInt(), Align.CENTER, bold = true)
            hud.text("${r.score} points", hud.width / 2f, y + h / 2f + 16f * d, 16f, Colors.WHITE, Align.CENTER)
        }
    }

    private fun drawStandings(hud: Hud) {
        val d = hud.density
        val me = lanes[0].runner
        val rows = (listOf(Triple(me.name + " (you)", me.z, me.crashed) to me.color) +
            ghosts.values.map { Triple(it.name, it.distance, it.crashed) to it.color }).sortedByDescending { it.first.second }
        val x = hud.safeLeft + 12f * d
        var y = hud.safeTop + 110f * d
        val w = 170f * d
        hud.panel(x, y - 8f * d, w, rows.size * 26f * d + 16f * d, 0x99101418.toInt(), 14f)
        rows.forEachIndexed { i, (info, color) ->
            val (name, dist, crashed) = info
            hud.batch.circle(x + 16f * d, y + 9f * d, 6f * d, if (crashed) 0x66FFFFFF else color)
            hud.text("${i + 1}. ${name.take(12)}", x + 28f * d, y, 13f, if (crashed) 0x88FFFFFF.toInt() else Colors.WHITE, shadow = false)
            hud.text("${dist.toInt()}m", x + w - 10f * d, y, 13f, if (crashed) 0x88FFFFFF.toInt() else 0xFFFFD27A.toInt(), Align.RIGHT, shadow = false)
            y += 26f * d
        }
    }

    private fun drawCountdown(hud: Hud) {
        if (phase != Phase.COUNTDOWN) return
        val left = countdownEnd - System.currentTimeMillis()
        val n = ((left + 999) / 1000).toInt().coerceAtLeast(1)
        val frac = (left % 1000) / 1000f
        val size = 90f + (1f - frac) * 20f
        val text = if (n > 3) "Get ready" else "$n"
        if (split) {
            hud.text(text, hud.width / 2f, hud.height * 0.75f - hud.dp(size) / 2f, if (n > 3) 30f else size, Colors.WHITE, Align.CENTER, bold = true)
        } else {
            hud.text(text, hud.width / 2f, hud.height * 0.4f - hud.dp(size) / 2f, if (n > 3) 34f else size, Colors.WHITE, Align.CENTER, bold = true)
            if (net != null) hud.text("Race starts on every phone at the same moment", hud.width / 2f, hud.height * 0.4f + hud.dp(70f), 14f, 0xCCFFFFFF.toInt(), Align.CENTER)
        }
    }

    private fun drawResults(hud: Hud) {
        val d = hud.density
        val t = MathX.clamp01((System.currentTimeMillis() - overAt) / 400f)
        hud.dim(0.6f * t)
        val w = min(hud.width - 32f * d, 380f * d)
        val list: List<Pair<String, String>> = when {
            results != null -> results!!.mapIndexed { i, (n, sc, dist) -> "${i + 1}. $n" to if (raceGoal > 0f) "${dist.toInt()} m" else "$sc" }
            split -> lanes.map { it.runner }.sortedByDescending { it.score }.map { it.name to "${it.score}" }
            turns -> turnScores.map { it.first to "${it.second}" }
            else -> emptyList()
        }
        val h = (260f + list.size * 30f) * d
        val x = (hud.width - w) / 2f
        val y = (hud.height - h) / 2f + (1f - t) * 50f * d
        hud.solidPanel(x, y, w, h, 0xF2141922.toInt(), 26f)
        val me = lanes[0].runner
        val title = when {
            net != null && results == null -> "Waiting for the others…"
            net != null -> if (results?.firstOrNull()?.first == me.name) "You win!" else "Race over"
            split -> winnerText()
            turns && turnScores.size == 1 -> "${turnScores[0].first}: ${turnScores[0].second}"
            turns -> turnScores.maxByOrNull { it.second }?.let { "${it.first} wins!" } ?: "Done"
            else -> if (newRecord) "New best!" else "Crashed!"
        }
        hud.text(title, x + w / 2f, y + 22f * d, 26f, Colors.WHITE, Align.CENTER, bold = true, shadow = false)
        var ly = y + 64f * d
        if (!split && !turns) {
            hud.text("${me.score} points · ${me.coins} coins · ${me.z.toInt()} m", x + w / 2f, ly, 15f, 0xCCFFFFFF.toInt(), Align.CENTER, shadow = false)
            ly += 22f * d
            if (net == null) hud.text("Best: ${ctx.storage.best("score")}", x + w / 2f, ly, 13f, 0x99FFFFFF.toInt(), Align.CENTER, shadow = false)
            ly += 30f * d
        }
        for ((name, value) in list) {
            hud.text(name, x + 24f * d, ly, 15f, Colors.WHITE, shadow = false)
            hud.text(value, x + w - 24f * d, ly, 15f, 0xFFFFD27A.toInt(), Align.RIGHT, shadow = false)
            ly += 30f * d
        }
        val by = y + h - 66f * d
        val bw = (w - 48f * d) / 2f
        val againLabel = when {
            turns && turnScores.size == 1 -> "Next player"
            net != null && net?.isHost != true -> "Host restarts"
            net != null -> "Race again"
            else -> "Run again"
        }
        if (hud.button("again", againLabel, x + 16f * d, by, bw, 48f * d, ButtonStyle.PRIMARY, enabled = net == null || net?.isHost == true)) restart()
        if (hud.button("exit", "Exit", x + 32f * d + bw, by, bw, 48f * d, ButtonStyle.SECONDARY)) ctx.ui.exit(GameResult("Run over", me.score))
    }

    companion object {
        const val ID = "runner"
        private val ACCENT = 0xFFFFB03A.toInt()
        private const val MSG_STATE = 32
        private const val MSG_CRASH = 33
        private const val MSG_FINISH = 34
        private const val MSG_RESULTS = 35
        private const val MSG_RESTART = 36

        val descriptor = GameDescriptor(
            id = ID,
            title = "Runner",
            tagline = "Endless train-yard runner · race your friends",
            description = "Swipe through an endless rail yard: dodge trains, jump hurdles, roll under barriers, ride ramps onto roofs and grab power-ups. " +
                "Duel on one phone in split screen, take turns, or race friends on Wi-Fi, hotspot or online — everyone runs the same track.",
            accent = ACCENT,
            glyph = "🏃",
            orientation = GameOrientation.PORTRAIT,
            dimension = GameDimension.D3,
            minPlayers = 1,
            maxPlayers = 8,
            modes = setOf(PlayMode.SOLO, PlayMode.SAME_DEVICE, PlayMode.LAN, PlayMode.ONLINE),
            options = listOf(
                GameOption("duel", "Same-phone mode", listOf(Choice("split", "Split-screen duel", "Both run at once, face to face"), Choice("turns", "Take turns", "Same track, best score wins")), "split", setOf(PlayMode.SAME_DEVICE)),
                GameOption("race", "Race", listOf(Choice("survival", "Last runner standing", "Highest score when everyone has crashed"), Choice("2k", "First to 2 km"), Choice("5k", "First to 5 km")), "survival",
                    setOf(PlayMode.LAN, PlayMode.ONLINE)),
            ),
            sameDeviceLabel = "Duel on this phone",
            sameDevicePlayers = 2,
            preferredIsland = IslandStyle.CAMERA,
            version = 1,
            factory = { OeaRunner() },
        )
    }
}
