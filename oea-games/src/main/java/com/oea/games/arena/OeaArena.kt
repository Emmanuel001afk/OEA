package com.oea.games.arena

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
import com.oea.engine.fx.VfxSystem
import com.oea.engine.graphics.Align
import com.oea.engine.graphics.CharFx
import com.oea.engine.graphics.CharRig
import com.oea.engine.graphics.Environment
import com.oea.engine.graphics.Frame
import com.oea.engine.graphics.Material
import com.oea.engine.graphics.Mesh
import com.oea.engine.graphics.MeshBuilder
import com.oea.engine.graphics.ParticlePreset
import com.oea.engine.graphics.ParticleSystem
import com.oea.engine.graphics.PerspectiveCamera
import com.oea.engine.graphics.Renderer3D
import com.oea.engine.graphics.Textures
import com.oea.engine.graphics.ui
import com.oea.engine.graphics.world
import com.oea.engine.input.LookPad
import com.oea.engine.input.VirtualButton
import com.oea.engine.input.VirtualJoystick
import com.oea.engine.island.IslandStyle
import com.oea.engine.island.LiveActivity
import com.oea.engine.math.Colors
import com.oea.engine.math.Mat4
import com.oea.engine.math.MathX
import com.oea.engine.math.Vec3
import com.oea.engine.net.ByteWriter
import com.oea.engine.net.session.NetSession
import com.oea.games.CharacterStudio
import com.oea.engine.net.sync.InputHistory
import com.oea.engine.net.sync.SnapshotBuffer
import com.oea.engine.physics.Layers
import com.oea.engine.physics.RayHit
import com.oea.engine.ui.ButtonStyle
import com.oea.engine.ui.Hud
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * OEA Send Off: a mobile battle royale foundation. Skydive onto an island,
 * loot weapons, armour and medkits, survive a shrinking zone and outlast
 * bots and friends. Host-authoritative networking at 30 Hz with client-side
 * prediction and reconciliation, snapshot interpolation of other players and
 * server-side lag-compensated hit detection.
 */
class OeaArena : OeaGame() {
    /** What the client knows about a player (from snapshots). */
    private class Actor(val id: Int, var name: String, var color: Int) {
        val buffer = SnapshotBuffer(5, delayMs = 110L, angleMask = (1 shl 3) or (1 shl 4))
        val view = FloatArray(5)
        var flags = 1
        var health = 100; var armor = 0; var helmet = 0
        var weapon = 0
        var shots = 0
        var kills = 0
        var team = 0
        var gunLevel = 0
        var walk = 0f
        var lastX = 0f; var lastZ = 0f
        var speed01 = 0f
        /** Weather/wind state for this soldier (wet, dusty, hair in the wind). */
        val fx = CharFx()
        val alive get() = flags and 1 != 0
    }

    private lateinit var map: ArenaMap
    private lateinit var sim: ArenaSim
    private var isAuthority = true
    private var net: NetSession? = null
    private var localId = 0
    private var local: ArenaPlayer? = null
    private val actors = LinkedHashMap<Int, Actor>()
    private val prev = HashMap<Int, FloatArray>()
    private val history = InputHistory<FloatArray>(128)
    private var seq = 0
    private val sentCmds = ArrayDeque<ByteArray>()
    private val hostQueues = HashMap<Int, ArrayDeque<PlayerInput>>()
    private var snapTimer = 0f
    private var zoneSnapshot = FloatArray(6)
    private var matchOver = false
    private var winnerName: String? = null
    private var overAt = 0L
    private var spectate = -1
    private var hintUntil = 0L

    // Character Studio (live creator) and the VFX pipeline (fluid gas, sparks,
    // weather, dynamic lights) — both are part of the match from the first frame.
    private lateinit var studio: CharacterStudio
    private var studioRig: CharRig? = null
    private val myFx = CharFx()
    private val actorFx = HashMap<Int, CharFx>()
    private lateinit var vfx: VfxSystem
    private var fogTimer = 0f

    // Controls
    private val joystick = VirtualJoystick(floatArrayOf(0f, 0.32f, 0.42f, 1f), 60f, homeX = 0.14f, homeY = 0.74f)
    private val look = LookPad(floatArrayOf(0.42f, 0.12f, 1f, 1f))
    private val fire = VirtualButton(0.86f, 0.7f, radiusDp = 44f, label = "FIRE", color = 0xFFFF5A3C.toInt())
    private val fire2 = VirtualButton(0.1f, 0.36f, radiusDp = 30f, label = "◎", color = 0xFFFF5A3C.toInt())
    private val adsBtn = VirtualButton(0.75f, 0.52f, radiusDp = 27f, label = "⊕")
    private val jumpBtn = VirtualButton(0.93f, 0.46f, radiusDp = 27f, label = "↑")
    private val crouchBtn = VirtualButton(0.95f, 0.88f, radiusDp = 24f, label = "▼")
    private val reloadBtn = VirtualButton(0.74f, 0.88f, radiusDp = 24f, label = "R")
    private val healBtn = VirtualButton(0.6f, 0.9f, radiusDp = 23f, label = "+")
    private val pickupBtn = VirtualButton(0.6f, 0.62f, radiusDp = 30f, label = "PICK", color = 0xFF57C8FF.toInt())
    private var lookYaw = 0f
    private var lookPitch = 0f
    private var adsOn = false
    private var crouchOn = false
    private var jumpLatch = false
    private var reloadLatch = false
    private var slotLatch = -1
    private var pickupLatch = -1
    private val inputNow = PlayerInput()

    // Aim tuning — editable live from the pause menu and persisted per device.
    private var adsSens = 0.85f
    private var invertY = false
    /** Turn-rate cap in rad/s; 0 = unlimited. */
    private var turnLimit = 0f

    // Player progression (survives matches on this device).
    private var xpTotal = 0
    private var xpEarned = 0
    private var levelNow = 1
    private var levelBefore = 1

    /** Backdrop clear colour matched to the terrain level. */
    private var skyClear = 0xFF87B6E6.toInt()

    // Presentation
    private val camera = PerspectiveCamera(70f)
    private val camPos = Vec3()
    private val env = Environment.day().apply { fogDensity = 0.0042f; shadowDistance = 42f; fogColor = 0xFFBFD6EE.toInt() }
    private val envDay = Environment.day().apply { fogDensity = 0.0042f }
    private val envNight = Environment.night().apply { fogDensity = 0.010f }
    /** Match rules: "br", "tdm", "gun", "last_day", "lights_out" or "haunted". */
    private var rules = "br"
    private var waveTimer = 0f
    private var waveNumber = 0
    private var dayNightClock = 0f
    private var lastDarkness = 0f
    private var pendingExtraBots = 0
    private fun isAuthorityNow(launch: com.oea.engine.core.GameLaunch) = net == null || net!!.isHost
    private val hit = RayHit()
    private val m = Mat4()
    private val tmp = Vec3()
    private val tracers = ArrayList<FloatArray>()
    private val sparks = ParticleSystem(1200, Textures.softCircle(64, 0.2f), additive = true, velocityAligned = true)
    private val puffs = ParticleSystem(500, Textures.softCircle(64, 0f), additive = false)
    private val damageNumbers = ArrayList<FloatArray>() // x, y, z, value, age, head
    private val killFeed = ArrayList<Pair<String, Long>>()
    private var hitMarker = 0f
    private var hitMarkerKill = false
    private var damageFrom = 0f
    private var damageAngle = 0f
    /** Presentation of the respawn wait (the simulation owns the real timer). */
    private var respawnCountdown = -1f
    private val tracerMat = Material(color = 0xFFFFE6A0.toInt(), emissive = 0xFFFFD27A.toInt(), emissiveStrength = 3f, unlit = true, additive = true, transparent = true, depthWrite = false, castShadows = false, fog = false)
    private val zoneMat = Material(color = 0x553FA9FF, emissive = 0xFF3FA9FF.toInt(), emissiveStrength = 0.6f, unlit = true, transparent = true, additive = true, doubleSided = true, depthWrite = false, castShadows = false, fog = false)
    private val skin = Material(color = 0xFFE0B48C.toInt(), specular = 0.2f)
    private val pants = Material(color = 0xFF2E3440.toInt(), specular = 0.15f)
    private val vestMat = Material(color = 0xFF4E5D3A.toInt(), specular = 0.2f)
    private val helmetMat = Material(color = 0xFF3B4A2C.toInt(), specular = 0.35f, shininess = 40f)
    private val gunMat = Material(color = 0xFF1E2126.toInt(), specular = 0.8f, shininess = 70f)
    private val canopyMat = Material(color = 0xFFFF7043.toInt(), doubleSided = true, specular = 0.2f)
    private val lootGlow = HashMap<LootKind, Material>()
    private val shirts = HashMap<Int, Material>()
    private val canopyMesh: Mesh by lazy { MeshBuilder().lathe((0..8).map { i -> val a = i / 8f * MathX.HALF_PI; com.oea.engine.math.Vec2(cos(a) * 2.4f, sin(a) * 1.0f) }, 24, capBottom = false, capTop = false).build() }
    private val waterMesh: Mesh by lazy { MeshBuilder().plane(0f, 0f, 0f, 900f, 900f, 2, 2).build() }
    private val gunMeshes = HashMap<Weapon, Mesh>()

    private lateinit var sShot: Map<Weapon, Sound>
    private lateinit var sHit: Sound
    private lateinit var sHead: Sound
    private lateinit var sReload: Sound
    private lateinit var sStep: Sound
    private lateinit var sPick: Sound
    private lateinit var sWin: Sound
    private lateinit var sLose: Sound

    override val fixedStepHz: Int get() = 30

    override fun onCreate() {
        val launch = ctx.launch
        net = ctx.net
        val seed = net?.seed?.takeIf { it != 0L } ?: launch.seed
        val settings = net?.matchSettings ?: launch.options
        val configuredBots = (settings["bots"] ?: launch.option("bots", "9")).toIntOrNull()?.coerceIn(0, 28) ?: 9
        val skill = when (settings["difficulty"] ?: launch.option("difficulty", "normal")) { "easy" -> 0.25f; "hard" -> 0.9f; else -> 0.55f }
        val fast = (settings["zone"] ?: launch.option("zone", "normal")) == "fast"
        rules = settings["rules"] ?: launch.option("rules", "br")
        val terrain = settings["terrain"] ?: launch.option("terrain", "island")
        val loadout = settings["loadout"] ?: launch.option("loadout", "all")
        map = ArenaMap(seed, terrain)
        sim = ArenaSim(seed, map, fast, skill, rules, loadout)
        // Character Studio + the physics VFX pipeline live with the match.
        studio = CharacterStudio(ctx.storage)
        studioRig = studio.rig()
        vfx = VfxSystem({ x, z -> map.heightAt(x, z) }, 0f, 0f, ArenaMap.SIZE * 0.5f)
        vfx.fluid.buildObstacles { x, z -> map.heightAt(x, z) }
        // Houses are solid volumes to gas: smoke curls around their walls and
        // spills over roofs instead of passing through a static grid.
        for (house in map.houses) {
            vfx.fluid.setSolidBox(house.x0 - 0.2f, house.floor, house.z0 - 0.2f, house.x1 + 0.2f, house.floor + 3.5f, house.z1 + 0.2f)
        }
        vfx.sparkDensity = ctx.graphics.quality.particleDensity
        // Every island level carries its own weather physics: rain soaks the
        // squad, alpine snow drifts on the wind, badlands dust settles on kit.
        when (terrain) {
            "highlands" -> { vfx.snow = 0.32f; vfx.windX = 0.8f; vfx.cold = 0.5f }
            "badlands" -> { vfx.dust = 0.45f; vfx.windX = 1.15f; vfx.windZ = 0.5f; vfx.fluid.dissipation = 0.8f }
            else -> { vfx.rain = 0.14f; vfx.windX = 0.35f }
        }
        if (rules == "haunted" || rules == "lights_out") {
            vfx.fogBank(0f, map.heightAt(0f, 0f), 0f, radius = 18f, density = 0.8f)
        }
        // Every terrain level carries its own sky and haze.
        when (terrain) {
            "highlands" -> {
                envDay.skyTop = 0xFF3D74C4.toInt(); envDay.skyHorizon = 0xFFD2DEEE.toInt(); envDay.fogColor = 0xFFC9D6E4.toInt()
                envDay.fogDensity = 0.0052f; envDay.groundAmbient = 0xFF5E6158.toInt(); envDay.sunColor = 0xFFFFF4E4.toInt()
                skyClear = 0xFF9FB8D6.toInt()
            }
            "badlands" -> {
                envDay.skyTop = 0xFF3E6FC0.toInt(); envDay.skyHorizon = 0xFFE8C9A8.toInt(); envDay.fogColor = 0xFFD9B08C.toInt()
                envDay.fogDensity = 0.0058f; envDay.groundAmbient = 0xFF7A5B42.toInt(); envDay.sunColor = 0xFFFFE0B8.toInt()
                envDay.sunIntensity = 2.6f
                skyClear = 0xFFD8B48C.toInt()
            }
        }
        when (rules) {
            // Permanent night; the moon and your muzzle flash are the light.
            "lights_out" -> { env.copyFrom(envNight); env.fogDensity = 0.014f; env.ambientIntensity = 0.42f }
            // A sick-green night with dense fog: zombies find you before you find them.
            "haunted" -> { env.copyFrom(envNight); env.fogColor = 0xFF16241C.toInt(); env.fogDensity = 0.017f; env.skyAmbient = 0xFF2E4638.toInt() }
            // Day turns to night while wave after wave lands on the island.
            "last_day" -> { env.copyFrom(envDay); sim.zone.enabled = false }
            // Competitive daylight: crisper air, quicker reads.
            "tdm", "gun" -> { env.copyFrom(envDay); env.fogDensity = 0.0034f }
            else -> env.copyFrom(envDay)
        }
        if (rules == "last_day" || rules == "haunted" || rules == "lights_out") {
            // Survival crowds: more undead, and they just keep coming.
            if (isAuthorityNow(launch)) {
                val extra = when (rules) {
                    "haunted" -> 18
                    "lights_out" -> 12
                    else -> 9
                }
                // extra bots are added after the launch bots below
                pendingExtraBots = extra
            }
            waveTimer = 35f
            waveNumber = 0
        }
        isAuthority = net == null || net!!.isHost
        localId = net?.localId ?: 0
        if (isAuthority) {
            val humans = net?.players ?: emptyList()
            if (humans.isEmpty()) sim.addPlayer(0, ctx.settings.playerName, ctx.settings.playerColor, false)
            else humans.forEach { sim.addPlayer(it.id, it.name, it.color, false) }
            val humanCount = if (humans.isEmpty()) 1 else humans.size
            val minimumBots = when (rules) {
                // A solo competitive queue still needs an opponent; a network
                // team match with two humans already has both sides covered.
                "tdm" -> (2 - humanCount).coerceAtLeast(0)
                "gun" -> if (humanCount == 1) 1 else 0
                else -> 0
            }
            repeat(max(configuredBots, minimumBots)) { i -> sim.addPlayer(sim.allocBotId(), BOT_NAMES[i % BOT_NAMES.size], EngineColors[(i + 2) % EngineColors.size], true) }
            repeat(pendingExtraBots) { i -> sim.addPlayer(sim.allocBotId(), BOT_NAMES[(i + 3) % BOT_NAMES.size], EngineColors[(i + 5) % EngineColors.size], true) }
            pendingExtraBots = 0
            sim.events = simEvents
            sim.compensation = { p -> if (p.id == localId) 0L else (net?.rttTo(p.id)?.toLong() ?: 0L) / 2 + 110L }
            local = sim.players[localId]
            if (net != null) broadcastRoster()
        } else {
            // Client: predict our own player with the same movement code.
            local = ArenaPlayer(localId, ctx.settings.playerName, ctx.settings.playerColor, false).also {
                val tmpSim = sim.addPlayer(localId, ctx.settings.playerName, ctx.settings.playerColor, false)
                it.ctrl.teleport(tmpSim.pos.x, tmpSim.pos.y, tmpSim.pos.z)
                sim.players.remove(localId)
            }
            sim.events = null
        }
        local?.let { lookYaw = it.yaw; lookPitch = 0f; camPos.set(it.pos.x, it.pos.y + 3f, it.pos.z - 4f) }
        sShot = mapOf(Weapon.PAN to ctx.audio.load("ar-pan", Sfx.HIT), Weapon.PISTOL to ctx.audio.load("ar-pistol", Sfx.SHOT_PISTOL),
            Weapon.SMG to ctx.audio.load("ar-smg", Sfx.SHOT_PISTOL), Weapon.RIFLE to ctx.audio.load("ar-rifle", Sfx.SHOT_RIFLE),
            Weapon.SHOTGUN to ctx.audio.load("ar-shotgun", Sfx.SHOT_SHOTGUN), Weapon.SNIPER to ctx.audio.load("ar-sniper", Sfx.SHOT_SNIPER),
            Weapon.REVOLVER to ctx.audio.load("ar-revolver", Sfx.SHOT_SHOTGUN), Weapon.MARKSMAN to ctx.audio.load("ar-marksman", Sfx.SHOT_RIFLE),
            Weapon.LMG to ctx.audio.load("ar-lmg", Sfx.SHOT_RIFLE))
        sHit = ctx.audio.load("ar-hit", Sfx.HIT)
        sHead = ctx.audio.load("ar-head", Sfx.HEADSHOT)
        sReload = ctx.audio.load("ar-reload", Sfx.RELOAD)
        sStep = ctx.audio.load("ar-step", Sfx.FOOTSTEP)
        sPick = ctx.audio.load("ar-pick", Sfx.POP)
        sWin = ctx.audio.load("ar-win", Sfx.WIN)
        sLose = ctx.audio.load("ar-lose", Sfx.LOSE)
        ctx.audio.playMusic("arena", MusicTrack(bpm = 96, bars = 8, root = 50, minor = true, progression = listOf(0, 3, 5, 4), drums = true, lead = false, pad = true, energy = 0.5f, seed = 3))
        sparks.density = ctx.graphics.quality.particleDensity
        puffs.density = ctx.graphics.quality.particleDensity
        hintUntil = System.currentTimeMillis() + 9000L
        // Aim tuning and progression come from the device's own settings.
        adsSens = ctx.settings.adsSensitivity
        invertY = ctx.settings.invertLookY
        turnLimit = ctx.settings.turnSpeedLimit * MathX.DEG_TO_RAD
        look.maxDeltaPx = 420f // only absurd pointer-resync jumps are discarded
        xpTotal = ctx.storage.getInt("xp")
        levelBefore = levelFor(xpTotal)
        levelNow = levelBefore
    }

    /** Progression: level 2 at 400 XP, then quadratically harder. */
    private fun levelFor(xp: Int): Int = 1 + sqrt(xp.coerceAtLeast(0) / 400f).toInt()

    private fun xpForLevel(level: Int): Int = 400 * (level - 1) * (level - 1)

    /** Awards XP and toasts level-ups through the island. */
    private fun awardXp(amount: Int, reason: String) {
        if (amount <= 0) return
        xpEarned += amount
        xpTotal += amount
        val nl = levelFor(xpTotal)
        if (nl > levelNow) {
            levelNow = nl
            ctx.island.event("Level $nl · $reason", "★", 2600, important = true)
            ctx.audio.play(sPick, 0.9f, pitch = 1.35f)
        }
    }

    // ---------------------------------------------------------------- authority events

    private val simEvents = object : ArenaEvents {
        override fun onShot(p: ArenaPlayer, hitX: Float, hitY: Float, hitZ: Float, hitPlayer: Boolean) {
            addTracer(p.pos.x, p.pos.y + p.eyeHeight - 0.15f, p.pos.z, p.yaw, hitX, hitY, hitZ)
            impact(hitX, hitY, hitZ, hitPlayer)
            playShot(p.weapon, p.pos.x, p.pos.y, p.pos.z, p.id == localId)
        }
        override fun onHit(shooter: ArenaPlayer, victim: ArenaPlayer, damage: Float, headshot: Boolean) {
            onHitFeedback(shooter.id, victim.id, damage, headshot, victim.pos.x, victim.pos.y + 1.4f, victim.pos.z, shooter.pos.x, shooter.pos.z)
            net?.broadcast(EV_HIT) { w -> w.u8(shooter.id).u8(victim.id).u8(damage.toInt().coerceIn(0, 255)).bool(headshot) }
        }
        override fun onKill(killer: ArenaPlayer?, victim: ArenaPlayer, weapon: Weapon, headshot: Boolean) {
            onKillFeed(killer?.id ?: -1, victim.id, weapon, headshot)
            net?.broadcast(EV_KILL) { w -> w.u8(killer?.id ?: 255).u8(victim.id).u8(weapon.ordinal).bool(headshot).u8(victim.place) }
        }
        override fun onLootTaken(p: ArenaPlayer, loot: Loot) {
            if (p.id == localId) ctx.audio.play(sPick, 0.6f)
            net?.broadcast(EV_LOOT_TAKEN) { it.varint(loot.id) }
        }
        override fun onLootSpawned(loot: Loot) {
            net?.broadcast(EV_LOOT_SPAWN) { w -> w.varint(loot.id).u8(loot.kind.ordinal).u8(loot.value).f32(loot.x).f32(loot.y).f32(loot.z) }
        }
        override fun onZone(phase: Int, shrinking: Boolean) {
            ctx.island.event(if (shrinking) "The zone is closing in" else "Zone ${phase + 1} reached", "◎", 2500)
        }
        override fun onMatchOver(winner: ArenaPlayer?) {
            net?.broadcast(EV_OVER) { it.u8(winner?.id ?: 255) }
            endMatch(winner?.id ?: -1, winner?.name)
        }
    }

    private fun broadcastRoster() {
        net?.broadcast(EV_ROSTER) { w ->
            w.u8(sim.players.size)
            sim.players.values.forEach { p -> w.u8(p.id).string(p.name).i32(p.color) }
        }
    }

    // ---------------------------------------------------------------- simulation tick (30 Hz)

    override fun onFixedUpdate(dt: Float) {
        pollNetwork()
        val me = local ?: return
        buildInput(inputNow)
        if (isAuthority) {
            sim.players[localId]?.input?.let { if (me.alive) it.copyFrom(inputNow) }
            // Remote clients' queued commands.
            for ((id, q) in hostQueues) {
                val p = sim.players[id] ?: continue
                val cmd = q.removeFirstOrNull() ?: continue
                p.input.copyFrom(cmd)
                p.lastAck = cmd.seq
            }
            for (p in sim.players.values) prev.getOrPut(p.id) { FloatArray(3) }.let { it[0] = p.pos.x; it[1] = p.pos.y; it[2] = p.pos.z }
            if (sim.survival && !matchOver) {
                waveTimer -= dt
                if (waveTimer <= 0f) {
                    waveNumber++
                    val aliveBots = sim.players.values.count { it.bot != null && it.alive }
                    val target = (9 + waveNumber * 2 + if (rules == "haunted") 6 else 0).coerceAtMost(28)
                    if (aliveBots < target) {
                        val spawn = (target - aliveBots).coerceAtMost(8)
                        repeat(spawn) { k -> sim.addPlayer(sim.allocBotId(), BOT_NAMES[k % BOT_NAMES.size], EngineColors[(k + 4) % EngineColors.size], true) }
                        if (spawn > 0) {
                            if (net != null) broadcastRoster()
                            ctx.island.event("Wave $waveNumber is coming", "🌊", 2500)
                        }
                    }
                    awardXp(50 * waveNumber.coerceAtMost(6), "Wave $waveNumber")
                    waveTimer = 40f
                }
            }
            sim.step(dt)
            snapTimer -= dt
            if (net != null && snapTimer <= 0f) { snapTimer = 1f / 20f; sendSnapshot() }
        } else if (me.alive && !matchOver) {
            prev.getOrPut(localId) { FloatArray(3) }.let { it[0] = me.pos.x; it[1] = me.pos.y; it[2] = me.pos.z }
            inputNow.seq = ++seq and 0xFFFF
            sim.move(me, inputNow, dt)
            history.push(inputNow.seq, floatArrayOf(me.pos.x, me.pos.y, me.pos.z, inputNow.moveX, inputNow.moveY, inputNow.yaw, inputNow.pitch,
                if (inputNow.crouch) 1f else 0f, if (inputNow.jump) 1f else 0f, if (inputNow.ads) 1f else 0f))
            sendCommand(inputNow)
        }
        jumpLatch = false; reloadLatch = false; slotLatch = -1; pickupLatch = -1
    }

    private val aim = FloatArray(2)

    /**
     * Third-person aim convergence: the body aims from its eye at whatever the
     * crosshair (camera centre ray) points at, so shots land on the crosshair
     * despite the over-the-shoulder camera offset.
     */
    private fun aimAngles(me: ArenaPlayer, out: FloatArray) {
        out[0] = lookYaw; out[1] = lookPitch
        if (me.skydiving) return
        val cp = cos(lookPitch)
        val fx = sin(lookYaw) * cp; val fy = sin(lookPitch); val fz = cos(lookYaw) * cp
        val dist = if (map.physics.raycast(camPos.x, camPos.y, camPos.z, fx, fy, fz, 260f, hit, Layers.WORLD or Layers.PROPS)) hit.distance else 260f
        if (dist < 2.5f) return
        val tx = camPos.x + fx * dist; val ty = camPos.y + fy * dist; val tz = camPos.z + fz * dist
        val dx = tx - me.pos.x; val dy = ty - (me.pos.y + me.eyeHeight); val dz = tz - me.pos.z
        val flat = sqrt(dx * dx + dz * dz)
        if (flat < 0.5f) return
        out[0] = kotlin.math.atan2(dx, dz)
        out[1] = MathX.clamp(kotlin.math.atan2(dy, flat), -1.2f, 1.1f)
    }

    private fun buildInput(inp: PlayerInput) {
        val me = local
        if (studio.open) {
            // Character Studio owns the screen: the soldier stands still and
            // the overlay's taps are the only input that matters.
            inp.moveX = 0f; inp.moveY = 0f
            inp.fire = false; inp.ads = false; inp.jump = false; inp.crouch = false
            inp.reload = false; inp.slot = -1; inp.heal = false; inp.pickup = -1
            if (me != null) aimAngles(me, aim) else { aim[0] = lookYaw; aim[1] = lookPitch }
            inp.yaw = aim[0]
            inp.pitch = aim[1]
            return
        }
        inp.moveX = joystick.value.x
        inp.moveY = joystick.value.y
        if (me != null) aimAngles(me, aim) else { aim[0] = lookYaw; aim[1] = lookPitch }
        inp.yaw = aim[0]
        inp.pitch = aim[1]
        inp.fire = fire.pressed || fire2.pressed
        inp.ads = adsOn
        inp.jump = jumpLatch
        inp.crouch = crouchOn
        inp.reload = reloadLatch
        inp.slot = slotLatch
        inp.heal = healBtn.pressed
        inp.pickup = pickupLatch
    }

    private fun sendCommand(inp: PlayerInput) {
        val s = net ?: return
        val w = ByteWriter(24)
        w.u16(inp.seq).q8(inp.moveX, -1f, 1f).q8(inp.moveY, -1f, 1f).angle(inp.yaw).q16(inp.pitch, -1.3f, 1.3f)
            .u8((if (inp.fire) 1 else 0) or (if (inp.ads) 2 else 0) or (if (inp.jump) 4 else 0) or (if (inp.crouch) 8 else 0) or (if (inp.reload) 16 else 0) or (if (inp.heal) 32 else 0))
            .u8(if (inp.slot in 0..1) inp.slot else 255).varint(inp.pickup + 1)
        sentCmds.addLast(w.toByteArray())
        while (sentCmds.size > 3) sentCmds.removeFirst()
        // Each packet repeats the last commands, so a lost packet costs nothing.
        s.sendToHost(MSG_CMD, reliable = false) { out ->
            out.u8(sentCmds.size)
            for (c in sentCmds) out.bytes(c)
        }
    }

    private fun sendSnapshot() {
        val s = net ?: return
        val z = sim.zone
        s.broadcast(MSG_SNAP, reliable = false) { w ->
            w.f32(sim.time).f32(z.cx).f32(z.cz).f32(z.radius).u8(z.phase).bool(z.shrinking).f32(z.timer).f32(z.toX).f32(z.toZ).f32(z.toR)
            w.u8(sim.players.size)
            for (p in sim.players.values) {
                val flags = (if (p.alive) 1 else 0) or (if (p.crouching) 2 else 0) or (if (p.aiming) 4 else 0) or (if (p.skydiving) 8 else 0) or
                    (if (p.parachute) 16 else 0) or (if (p.healProgress > 0f) 32 else 0)
                w.u8(p.id).u8(flags).f32(p.pos.x).f32(p.pos.y).f32(p.pos.z).angle(p.yaw).q16(p.pitch, -1.3f, 1.3f)
                    .u8(p.health.toInt().coerceIn(0, 255)).u8(p.armor).u8(p.helmet)
                    .u8(p.slots[0].ordinal).u8(p.slots[1].ordinal).u8(p.active).u8(p.mags[0]).u8(p.mags[1])
                p.ammo.forEach { w.u16(it.coerceIn(0, 65535)) }
                w.u8(p.medkits).u8(p.shots and 0xFF).u8(p.kills).u16(p.lastAck and 0xFFFF)
                    .q8(p.healProgress, 0f, 1f).q8(p.reloadLeft, 0f, 4f)
                w.u8(p.team).u8(p.gunLevel)
            }
            w.u8(sim.teamScore[0].coerceIn(0, 255)).u8(sim.teamScore[1].coerceIn(0, 255))
        }
    }

    // ---------------------------------------------------------------- networking

    private fun pollNetwork() {
        val s = net ?: return
        s.poll { msg ->
            val r = msg.reader()
            when (msg.type) {
                MSG_CMD -> if (isAuthority) {
                    val n = r.u8()
                    val q = hostQueues.getOrPut(msg.from) { ArrayDeque() }
                    val last = sim.players[msg.from]?.lastAck ?: 0
                    repeat(n) {
                        val inp = PlayerInput()
                        inp.seq = r.u16(); inp.moveX = r.q8(-1f, 1f); inp.moveY = r.q8(-1f, 1f); inp.yaw = r.angle(); inp.pitch = r.q16(-1.3f, 1.3f)
                        val b = r.u8()
                        inp.fire = b and 1 != 0; inp.ads = b and 2 != 0; inp.jump = b and 4 != 0; inp.crouch = b and 8 != 0; inp.reload = b and 16 != 0; inp.heal = b and 32 != 0
                        inp.slot = r.u8().let { if (it == 255) -1 else it }
                        inp.pickup = r.varint() - 1
                        val newest = q.lastOrNull()?.seq ?: last
                        if (InputHistory.seqDiff(inp.seq, newest) > 0) q.addLast(inp)
                    }
                    while (q.size > 6) q.removeFirst()
                }
                MSG_SNAP -> if (!isAuthority) readSnapshot(r)
                EV_ROSTER -> {
                    val n = r.u8()
                    repeat(n) { val id = r.u8(); val name = r.string(64); val color = r.i32(); actors.getOrPut(id) { Actor(id, name, color) }.also { it.name = name; it.color = color } }
                }
                EV_HIT -> if (!isAuthority) {
                    val shooter = r.u8(); val victim = r.u8(); val dmg = r.u8(); val head = r.bool()
                    val v = actors[victim]?.view
                    val sh = actors[shooter]?.view
                    onHitFeedback(shooter, victim, dmg.toFloat(), head, v?.get(0) ?: local?.pos?.x ?: 0f, (v?.get(1) ?: 0f) + 1.4f, v?.get(2) ?: 0f, sh?.get(0) ?: 0f, sh?.get(2) ?: 0f)
                }
                EV_KILL -> if (!isAuthority) {
                    val killer = r.u8(); val victim = r.u8(); val weapon = Weapon.entries.getOrElse(r.u8()) { Weapon.PAN }; val head = r.bool(); val place = r.u8()
                    if (victim == localId) local?.place = place
                    onKillFeed(if (killer == 255) -1 else killer, victim, weapon, head)
                }
                EV_LOOT_TAKEN -> if (!isAuthority) { val id = r.varint(); sim.loot.firstOrNull { it.id == id }?.taken = true }
                EV_LOOT_SPAWN -> if (!isAuthority) {
                    val id = r.varint(); val kind = LootKind.entries[r.u8()]; val value = r.u8()
                    sim.loot += Loot(id, kind, value, r.f32(), r.f32(), r.f32())
                }
                EV_OVER -> if (!isAuthority) { val w = r.u8(); endMatch(if (w == 255) -1 else w, actors[w]?.name) }
                NetSession.MSG_PLAYER_LEFT -> if (isAuthority) sim.players[msg.from]?.let { if (it.alive) sim.damage(it, null, 999f, Weapon.PAN, false, zoneDamage = true) }
                NetSession.MSG_PLAYER_JOINED -> if (isAuthority) broadcastRoster()
                NetSession.MSG_MATCH_ENDED -> ctx.ui.exit(GameResult("Match ended"))
            }
        }
    }

    private fun readSnapshot(r: com.oea.engine.net.ByteReader) {
        val time = r.f32()
        zoneSnapshot[0] = r.f32(); zoneSnapshot[1] = r.f32(); zoneSnapshot[2] = r.f32()
        sim.zone.phase = r.u8(); sim.zone.shrinking = r.bool(); sim.zone.timer = r.f32()
        sim.zone.toX = r.f32(); sim.zone.toZ = r.f32(); sim.zone.toR = r.f32()
        sim.zone.cx = zoneSnapshot[0]; sim.zone.cz = zoneSnapshot[1]; sim.zone.radius = zoneSnapshot[2]
        val n = r.u8()
        val now = net?.now() ?: 0L
        repeat(n) {
            val id = r.u8(); val flags = r.u8()
            val x = r.f32(); val y = r.f32(); val z = r.f32(); val yaw = r.angle(); val pitch = r.q16(-1.3f, 1.3f)
            val health = r.u8(); val armor = r.u8(); val helmet = r.u8()
            val s0 = r.u8(); val s1 = r.u8(); val active = r.u8(); val m0 = r.u8(); val m1 = r.u8()
            val ammo = IntArray(4) { r.u16() }
            val meds = r.u8(); val shots = r.u8(); val kills = r.u8(); val ack = r.u16()
            val heal = r.q8(0f, 1f); val reload = r.q8(0f, 4f)
            val team = r.u8(); val gunLevel = r.u8()
            val a = actors.getOrPut(id) { Actor(id, "Player $id", EngineColors[id % EngineColors.size]) }
            if (id != localId && a.shots != shots && a.alive) onRemoteShots(a, (shots - a.shots) and 0xFF)
            if (id == localId && a.shots != shots) local?.let { me ->
                val n = min(3, (shots - a.shots) and 0xFF)
                val w = Weapon.entries.getOrElse(if (active == 0) s0 else s1) { Weapon.PAN }
                me.recoil += w.recoil * n
                playShot(w, x, y, z, true)
                addTracer(x, y + (if (flags and 2 != 0) 1.05f else 1.6f) - 0.15f, z, lookYaw, camPos.x + sin(lookYaw) * 60f, camPos.y + sin(lookPitch) * 60f, camPos.z + cos(lookYaw) * 60f)
            }
            a.flags = flags; a.health = health; a.armor = armor; a.helmet = helmet; a.weapon = (if (active == 0) s0 else s1); a.shots = shots; a.kills = kills
            a.team = team; a.gunLevel = gunLevel
            a.buffer.push(now, floatArrayOf(x, y, z, yaw, pitch))
            if (id == localId) {
                local?.let { it.team = team; it.gunLevel = gunLevel }
                reconcile(x, y, z, flags, health, armor, helmet, s0, s1, active, m0, m1, ammo, meds, kills, ack, heal, reload)
            }
        }
        sim.teamScore[0] = r.u8(); sim.teamScore[1] = r.u8()
        @Suppress("UNUSED_VARIABLE") val unused = time
    }

    /** Client: accept the authority's view of our player and replay unacknowledged inputs. */
    private fun reconcile(x: Float, y: Float, z: Float, flags: Int, health: Int, armor: Int, helmet: Int, s0: Int, s1: Int, active: Int,
                          m0: Int, m1: Int, ammo: IntArray, meds: Int, kills: Int, ack: Int, heal: Float, reload: Float) {
        val me = local ?: return
        me.health = health.toFloat(); me.armor = armor; me.helmet = helmet
        me.slots[0] = Weapon.entries[s0]; me.slots[1] = Weapon.entries[s1]; me.active = active; me.mags[0] = m0; me.mags[1] = m1
        ammo.copyInto(me.ammo); me.medkits = meds; me.kills = kills; me.healProgress = heal; me.reloadLeft = reload
        val wasAlive = me.alive
        me.alive = flags and 1 != 0
        me.skydiving = flags and 8 != 0
        me.parachute = flags and 16 != 0
        if (wasAlive && !me.alive) onLocalDeath()
        else if (!wasAlive && me.alive) { respawnCountdown = -1f; spectate = -1 }
        // Where did we predict to be at the acknowledged input?
        var predicted: FloatArray? = null
        history.forEachPending { s, st -> if (s == ack) predicted = st }
        history.acknowledge(ack)
        val p = predicted
        val err = if (p != null) MathX.length(p[0] - x, p[1] - y, p[2] - z) else 99f
        if (err > 0.3f || !me.alive) {
            me.ctrl.position.set(x, y, z)
            if (me.alive) {
                val replay = PlayerInput()
                history.forEachPending { _, st ->
                    replay.moveX = st[3]; replay.moveY = st[4]; replay.yaw = st[5]; replay.pitch = st[6]
                    replay.crouch = st[7] > 0.5f; replay.jump = st[8] > 0.5f; replay.ads = st[9] > 0.5f
                    sim.move(me, replay, 1f / fixedStepHz)
                }
            }
        }
    }

    private fun onRemoteShots(a: Actor, count: Int) {
        val v = a.view
        val w = Weapon.entries.getOrElse(a.weapon) { Weapon.PAN }
        val cp = cos(v[4])
        val dx = sin(v[3]) * cp; val dy = sin(v[4]); val dz = cos(v[3]) * cp
        val ey = v[1] + (if (a.flags and 2 != 0) 1.05f else 1.6f)
        val dist = if (map.physics.raycast(v[0], ey, v[2], dx, dy, dz, w.range, hit, Layers.WORLD or Layers.PROPS)) hit.distance else w.range
        repeat(min(count, 3)) {
            addTracer(v[0], ey - 0.15f, v[2], v[3], v[0] + dx * dist, ey + dy * dist, v[2] + dz * dist)
        }
        if (dist < w.range) impact(v[0] + dx * dist, ey + dy * dist, v[2] + dz * dist, false)
        playShot(w, v[0], v[1], v[2], false)
    }

    // ---------------------------------------------------------------- feedback

    private fun addTracer(x: Float, y: Float, z: Float, yaw: Float, hx: Float, hy: Float, hz: Float) {
        val mx = x + sin(yaw) * 0.6f; val mz = z + cos(yaw) * 0.6f
        tracers += floatArrayOf(mx, y, mz, hx, hy, hz, 0f)
        sparks.burst(ParticlePreset.MUZZLE, mx, y, mz, 4)
    }

    private fun impact(x: Float, y: Float, z: Float, player: Boolean) {
        if (player) sparks.burst(ParticlePreset.BLOOD_HIT, x, y, z, 10)
        else { sparks.burst(ParticlePreset.SPARKS, x, y, z, 8); puffs.burst(ParticlePreset.DUST, x, y, z, 3) }
    }

    private fun shotPitch(w: Weapon): Float = when (w) {
        Weapon.SMG -> 1.25f
        Weapon.PISTOL -> 1.1f
        Weapon.REVOLVER -> 0.78f
        Weapon.MARKSMAN -> 0.95f
        Weapon.SNIPER -> 0.9f
        Weapon.LMG -> 1.05f
        else -> 1f
    }

    private fun playShot(w: Weapon, x: Float, y: Float, z: Float, mine: Boolean) {
        val s = sShot[w] ?: return
        if (mine) {
            ctx.audio.play(s, 0.85f, pitch = shotPitch(w)); ctx.haptics.tick()
            // Muzzle physics: light on the hands, sparks, and a push of hot air.
            val fx = sin(lookYaw); val fz = cos(lookYaw)
            vfx.muzzle(x, y, z, fx, sin(lookPitch) * 0.3f, fz, heavy = if (w == Weapon.LMG || w == Weapon.SHOTGUN) 1.3f else 1f)
        } else {
            ctx.audio.play3D(s, x, y, z, camera, 90f, 0.9f, pitch = shotPitch(w))
            vfx.muzzle(x, y, z, rng1(), 0.2f, rng1(), heavy = if (w == Weapon.SNIPER) 1.2f else 0.8f)
        }
    }

    private fun rng1(): Float = (kotlin.random.Random.nextFloat() - 0.5f) * 1.2f

    private fun onHitFeedback(shooter: Int, victim: Int, damage: Float, head: Boolean, vx: Float, vy: Float, vz: Float, sx: Float, sz: Float) {
        // Sparks and dust where the round lands — they bounce off the ground.
        vfx.sparksAt(vx, vy, vz, if (head) 10 else 6, dirY = 0.8f, hot = if (head) 0xFFFFD54F.toInt() else 0xFFFFC27A.toInt())
        if (map.heightAt(vx, vz) > vy - 0.4f) vfx.dustPuff(vx, map.heightAt(vx, vz), vz, 0.5f)
        if (shooter == localId) {
            hitMarker = 0.25f
            ctx.audio.play(if (head) sHead else sHit, 0.7f)
            damageNumbers += floatArrayOf(vx, vy, vz, damage, 0f, if (head) 1f else 0f)
        }
        if (victim == localId) {
            damageFrom = 1.2f
            val me = local ?: return
            damageAngle = kotlin.math.atan2(sx - me.pos.x, sz - me.pos.z)
            ctx.haptics.click()
        }
    }

    private fun nameOfId(id: Int): String = sim.players[id]?.name ?: actors[id]?.name ?: if (id < 0) "the zone" else "Player $id"

    private fun onKillFeed(killer: Int, victim: Int, weapon: Weapon, head: Boolean) {
        val text = if (killer < 0) "${nameOfId(victim)} was caught by the zone" else "${nameOfId(killer)} ${weapon.glyph}${if (head) " ◎" else ""} ${nameOfId(victim)}"
        killFeed += text to System.currentTimeMillis()
        if (killFeed.size > 5) killFeed.removeAt(0)
        if (killer == localId) {
            hitMarkerKill = true; hitMarker = 0.45f
            awardXp(100 + if (head) 50 else 0, "Elimination")
            ctx.island.event("You eliminated ${nameOfId(victim)}", "◎", 2200)
        }
        if (victim == localId) onLocalDeath()
    }

    private fun onLocalDeath() {
        val me = local ?: return
        if (!isAuthority) me.alive = false
        ctx.audio.play(sLose)
        ctx.haptics.heavy()
        spectate = -1
        respawnCountdown = if (sim.respawnMode) (if (sim.gunGame) 1.5f else 4f) else -1f
        if (sim.respawnMode) ctx.island.event("Eliminated · respawning…", "◎", 2200)
        else ctx.island.event("Eliminated · #${me.place.coerceAtLeast(1)}", "◎", 3000, important = true)
    }

    private fun endMatch(winnerId: Int, name: String?) {
        if (matchOver) return
        matchOver = true
        winnerName = name
        overAt = System.currentTimeMillis()
        if (winnerId == localId) { ctx.audio.play(sWin); local?.place = 1 }
        ctx.storage.putInt("matches", ctx.storage.getInt("matches") + 1)
        if (winnerId == localId) ctx.storage.putInt("wins", ctx.storage.getInt("wins") + 1)
        val survivalWave = if (sim.survival) waveNumber else 0
        awardXp(
            (if (winnerId == localId) 500 else 120) + (local?.kills ?: 0) * 15 + survivalWave * 40,
            if (winnerId == localId) "Victory" else "Match complete",
        )
        ctx.storage.putInt("xp", xpTotal)
        ctx.storage.putInt("level", levelNow)
        ctx.storage.putInt("kills_total", ctx.storage.getInt("kills_total") + (local?.kills ?: 0))
        val headline = when {
            winnerId == localId && rules == "tdm" -> "Victory! Your team took it"
            winnerId == localId && rules == "gun" -> "Victory! Ladder complete"
            winnerId == localId -> "Victory! Last one standing"
            rules == "tdm" -> (if (sim.teamScore[0] > sim.teamScore[1]) "Blue" else "Orange") + " team wins"
            rules == "gun" -> "${name ?: "Someone"} finished the ladder"
            sim.survival -> "The waves won · you reached wave $waveNumber"
            else -> "${name ?: "Nobody"} won the match"
        }
        ctx.island.event(headline, "♛", 4000, important = true)
    }

    // ---------------------------------------------------------------- per frame

    override fun onUpdate(dt: Float) {
        val g = ctx.graphics
        val w = g.width.toFloat(); val h = g.height.toFloat(); val d = g.density
        val me = local
        val alive = me?.alive == true && !matchOver
        val sl = g.safeInsets[0].toFloat(); val sr = g.safeInsets[2].toFloat()
        // Controls: buttons first (they claim fingers), then the stick and the look area.
        val near = me?.let { sim.nearestLoot(it, LootKind.WEAPON) }
        pickupBtn.visible = alive && near != null && !(me?.skydiving ?: true)
        pickupBtn.label = near?.let { Weapon.entries[it.value].glyph } ?: "PICK"
        healBtn.visible = alive && (me?.medkits ?: 0) > 0
        healBtn.progress = me?.healProgress?.takeIf { it > 0f } ?: -1f
        // In the Character Studio the game controls stand down completely so
        // every tap lands on the studio overlay instead of the fire button.
        val controlsPaused = studio.open
        for (b in listOf(fire, fire2, adsBtn, jumpBtn, crouchBtn, reloadBtn, healBtn, pickupBtn)) {
            b.enabled = alive
            b.paused = controlsPaused
            b.update(ctx.input, w, h, d, sl, sr)
        }
        joystick.paused = controlsPaused
        look.paused = controlsPaused
        joystick.update(ctx.input, w, h, d)
        look.update(ctx.input, w, h)
        val zoom = if (adsOn) (me?.weapon?.zoom ?: 1f) else 1f
        // Sensitivity: base × ADS multiplier ÷ zoom, then the turn-rate cap —
        // the cap is what keeps a glitched pointer or a wild stick from ever
        // spinning the camera faster than a real hand can turn.
        var sens = 0.0038f * ctx.settings.lookSensitivity / d
        if (adsOn) sens *= adsSens
        sens /= zoom
        val maxDelta = 0.28f * min(w, h) // no real drag moves this far in one frame
        val dx = (look.deltaX + fire.dragDx + fire2.dragDx).coerceIn(-maxDelta, maxDelta)
        val dy = (look.deltaY + fire.dragDy + fire2.dragDy).coerceIn(-maxDelta, maxDelta)
        val inv = if (invertY) -1f else 1f
        val yawStep = dx * sens * 1.15f
        val pitchStep = dy * sens * inv
        if (turnLimit > 0f) {
            val maxYaw = turnLimit * dt
            val maxPitch = turnLimit * dt
            lookYaw = wrapYaw(lookYaw - yawStep.coerceIn(-maxYaw, maxYaw))
            lookPitch = MathX.clamp(lookPitch - pitchStep.coerceIn(-maxPitch, maxPitch), -1.2f, 1.1f)
        } else {
            lookYaw = wrapYaw(lookYaw - yawStep)
            lookPitch = MathX.clamp(lookPitch - pitchStep, -1.2f, 1.1f)
        }
        // Gamepad / keyboard: the same turn limit, so sticks and thumbs agree.
        val gp = ctx.input.gamepad
        if (gp.connected) {
            val gpYaw = gp.rightX * dt * 2.6f / zoom
            val gpPitch = gp.rightY * dt * 1.8f / zoom * inv
            if (turnLimit > 0f) {
                val maxYaw = turnLimit * dt
                lookYaw = wrapYaw(lookYaw - gpYaw.coerceIn(-maxYaw, maxYaw))
                lookPitch = MathX.clamp(lookPitch - gpPitch.coerceIn(-maxYaw, maxYaw), -1.2f, 1.1f)
            } else {
                lookYaw = wrapYaw(lookYaw - gpYaw)
                lookPitch = MathX.clamp(lookPitch - gpPitch, -1.2f, 1.1f)
            }
        }
        if (adsBtn.justPressed || ctx.input.keys.justPressed(KeyEvent.KEYCODE_SHIFT_LEFT) || gp.justPressed(KeyEvent.KEYCODE_BUTTON_L2)) adsOn = !adsOn
        if (crouchBtn.justPressed || gp.justPressed(KeyEvent.KEYCODE_BUTTON_B)) crouchOn = !crouchOn
        if (jumpBtn.justPressed || ctx.input.keys.justPressed(KeyEvent.KEYCODE_SPACE) || gp.justPressed(KeyEvent.KEYCODE_BUTTON_A)) { jumpLatch = true; crouchOn = false }
        if (reloadBtn.justPressed || ctx.input.keys.justPressed(KeyEvent.KEYCODE_R) || gp.justPressed(KeyEvent.KEYCODE_BUTTON_X)) reloadLatch = true
        if (pickupBtn.justPressed && near != null) pickupLatch = near.id
        if (gp.justPressed(KeyEvent.KEYCODE_BUTTON_Y)) slotLatch = 1 - (me?.active ?: 0)
        if (me?.weapon == Weapon.PAN) adsOn = false
        if (me != null && me.alive && me.recoil != 0f) {
            // Recoil from the simulation (host) or from our own shots (client) kicks the camera.
            lookPitch = MathX.clamp(lookPitch + me.recoil, -1.2f, 1.1f)
            me.recoil = 0f
        }
        // Visual effects.
        sparks.update(dt); puffs.update(dt)
        // Physics VFX: the air everyone breathes (weather, smoke, wind) and the
        // world's effect on the characters — wet cloth, dust on armour, hair
        // in the wind.
        vfx.sparkDensity = ctx.graphics.quality.particleDensity
        val fxList = ArrayList<CharFx>(actors.size + 1)
        fxList.add(myFx)
        for (a in actors.values) fxList.add(a.fx)
        if (isAuthority) for (p in sim.players.values) if (p.id != localId) fxList.add(actorFx.getOrPut(p.id) { CharFx() })
        vfx.update(dt, fxList)
        if (rules == "haunted") {
            fogTimer -= dt
            if (fogTimer <= 0f) {
                fogTimer = 2.5f
                val lx = local?.pos?.x ?: 0f; val lz = local?.pos?.z ?: 0f
                vfx.fogBank(lx, map.heightAt(lx, lz), lz, radius = 16f, density = 0.55f)
            }
        }
        val it = tracers.iterator()
        while (it.hasNext()) { val t = it.next(); t[6] += dt; if (t[6] > 0.09f) it.remove() }
        val dn = damageNumbers.iterator()
        while (dn.hasNext()) { val n = dn.next(); n[4] += dt; n[1] += dt * 0.8f; if (n[4] > 0.9f) dn.remove() }
        if (hitMarker > 0f) hitMarker -= dt else hitMarkerKill = false
        if (damageFrom > 0f) damageFrom -= dt
        if (respawnCountdown > 0f) {
            respawnCountdown -= dt
            if (me?.alive == true) respawnCountdown = -1f
        }
        if (rules == "last_day") {
            // 150 s day: day (0-60) -> dusk (60-75) -> night (75-135) -> dawn (135-150).
            dayNightClock = (dayNightClock + dt) % 150f
            val darkness = when {
                dayNightClock < 60f -> 0f
                dayNightClock < 75f -> (dayNightClock - 60f) / 15f
                dayNightClock < 135f -> 1f
                else -> 1f - (dayNightClock - 135f) / 15f
            }
            env.sunIntensity = MathX.lerp(envDay.sunIntensity, envNight.sunIntensity, darkness)
            env.ambientIntensity = MathX.lerp(envDay.ambientIntensity, envNight.ambientIntensity, darkness)
            env.fogDensity = MathX.lerp(envDay.fogDensity, envNight.fogDensity, darkness)
            // The world flips its light over dusk/dawn; unseen numbers would stay stuck.
            if (darkness > 0.5f && lastDarkness <= 0.5f) {
                env.sunColor = envNight.sunColor; env.skyAmbient = envNight.skyAmbient; env.groundAmbient = envNight.groundAmbient
                env.fogColor = envNight.fogColor; env.skyTop = envNight.skyTop; env.skyHorizon = envNight.skyHorizon; env.skyGround = envNight.skyGround
            } else if (darkness < 0.5f && lastDarkness >= 0.5f) {
                env.sunColor = envDay.sunColor; env.skyAmbient = envDay.skyAmbient; env.groundAmbient = envDay.groundAmbient
                env.fogColor = 0xFFBFD6EE.toInt(); env.skyTop = envDay.skyTop; env.skyHorizon = envDay.skyHorizon; env.skyGround = envDay.skyGround
            }
            lastDarkness = darkness
        }
        // Remote actors.
        val now = net?.now() ?: 0L
        for (a in actors.values) {
            if (a.id == localId) continue
            if (a.buffer.sample(now, a.view)) {
                val moved = MathX.length(a.view[0] - a.lastX, a.view[2] - a.lastZ)
                a.speed01 = MathX.clamp01(moved * 28f)
                a.walk += moved * 2.2f
                a.lastX = a.view[0]; a.lastZ = a.view[2]
            }
        }
        updateCamera(dt)
        publishIsland()
    }

    private fun updateCamera(dt: Float) {
        if (studio.open) {
            // Character Studio: a slow turntable around the live model.
            val me = local ?: return
            val orbit = ctx.time.elapsed.toFloat() * 0.35f
            val cx = me.pos.x + sin(orbit) * 2.4f
            val cy = me.pos.y + 1.55f
            val cz = me.pos.z + cos(orbit) * 2.4f
            camPos.set(cx, cy, cz)
            camera.position.set(camPos)
            camera.target.set(me.pos.x, me.pos.y + 1.05f, me.pos.z)
            camera.fovY = 52f * MathX.DEG_TO_RAD
            camera.near = 0.1f
            camera.far = 420f
            return
        }
        val alpha = ctx.time.alpha
        val target = viewTarget()
        val (px, py, pz) = target
        val me = local
        val yaw: Float; val pitch: Float
        if (me != null && me.alive) { yaw = lookYaw; pitch = lookPitch }
        else { yaw = lookYaw; pitch = MathX.clamp(lookPitch, -0.6f, 0.6f) }
        val skydive = me?.skydiving == true && me.alive
        val ads = adsOn && me?.alive == true && !skydive
        val zoom = if (ads) me?.weapon?.zoom ?: 1f else 1f
        val eyeY = py + (if (me?.crouching == true) 1.15f else 1.7f)
        val fx = sin(yaw); val fz = cos(yaw)
        val rx = -fz; val rz = fx
        val back = if (skydive) 7f else if (ads && zoom > 2f) 0.05f else if (ads) 1.6f else 3.0f
        val side = if (ads && zoom > 2f) 0f else 0.62f
        val cp = cos(pitch)
        val lx = fx * cp; val ly = sin(pitch); val lz = fz * cp
        var cx = px + rx * side - lx * back
        var cy = eyeY + 0.3f - ly * back
        var cz = pz + rz * side - lz * back
        // Keep the camera out of walls.
        val ox = px; val oy = eyeY; val oz = pz
        val ddx = cx - ox; val ddy = cy - oy; val ddz = cz - oz
        val dist = sqrt(ddx * ddx + ddy * ddy + ddz * ddz)
        if (dist > 0.05f && map.physics.raycast(ox, oy, oz, ddx / dist, ddy / dist, ddz / dist, dist, hit, Layers.WORLD)) {
            val k = max(0.05f, hit.distance - 0.25f) / dist
            cx = ox + ddx * k; cy = oy + ddy * k; cz = oz + ddz * k
        }
        cy = max(cy, map.heightAt(cx, cz) + 0.3f)
        camPos.set(cx, cy, cz)
        camera.position.set(camPos)
        camera.target.set(cx + lx * 10f, cy + ly * 10f, cz + lz * 10f)
        camera.fovY = (70f / zoom) * MathX.DEG_TO_RAD
        camera.near = 0.1f
        camera.far = 420f * ctx.graphics.quality.detailDistance.coerceAtLeast(0.7f)
        @Suppress("UNUSED_VARIABLE") val unused = alpha + dt
    }

    /** Interpolated position the camera follows (our player, or who we spectate). */
    private fun viewTarget(): Triple<Float, Float, Float> {
        val me = local
        val alpha = ctx.time.alpha
        if (me != null && me.alive) {
            val p = prev[localId]
            return if (p != null) Triple(MathX.lerp(p[0], me.pos.x, alpha), MathX.lerp(p[1], me.pos.y, alpha), MathX.lerp(p[2], me.pos.z, alpha))
            else Triple(me.pos.x, me.pos.y, me.pos.z)
        }
        // Spectate: the killer or anyone alive.
        if (isAuthority) {
            val s = sim.players[spectate]?.takeIf { it.alive } ?: sim.players[me?.killedBy ?: -1]?.takeIf { it.alive } ?: sim.players.values.firstOrNull { it.alive }
            if (s != null) { spectate = s.id; return Triple(s.pos.x, s.pos.y, s.pos.z) }
        } else {
            val a = actors[spectate]?.takeIf { it.alive } ?: actors.values.firstOrNull { it.alive && it.id != localId }
            if (a != null) { spectate = a.id; return Triple(a.view[0], a.view[1], a.view[2]) }
        }
        return Triple(me?.pos?.x ?: 0f, me?.pos?.y ?: 0f, me?.pos?.z ?: 0f)
    }

    private fun publishIsland() {
        val me = local ?: return
        if (matchOver) { ctx.island.end(); return }
        val alive = if (isAuthority) sim.aliveCount else actors.values.count { it.alive }
        ctx.island.update(LiveActivity(
            title = "Send Off · $alive alive",
            subtitle = if (me.alive) "${me.health.toInt()} HP · ${me.kills} kills" else "Eliminated · spectating",
            compactText = "$alive alive",
            trailing = "${me.kills} ◎",
            urgent = me.alive && me.health < 30f,
            preferred = IslandStyle.CARD,
        ))
    }

    override fun keepsRunningInBackground(): Boolean = net != null

    override fun onBack(): Boolean {
        if (studio.open) {
            studio.save()
            studio.open = false
            return true
        }
        openPauseMenu()
        return true
    }

    private fun openPauseMenu() {
        val sensLabel = String.format(java.util.Locale.US, "Look sensitivity · %.1f×", ctx.settings.lookSensitivity)
        val adsLabel = String.format(java.util.Locale.US, "ADS sensitivity · %.0f%%", adsSens * 100f)
        val turnLabel = if (turnLimit <= 0f) "Turn limit · Off" else String.format(java.util.Locale.US, "Turn limit · %.0f°/s", ctx.settings.turnSpeedLimit)
        val items = listOf(
            MenuItem("resume", "Resume"),
            MenuItem("sens", sensLabel),
            MenuItem("ads", adsLabel),
            MenuItem("turn", turnLabel),
            MenuItem("invert", "Invert look · " + if (invertY) "On" else "Off"),
            MenuItem("studio", "Character studio"),
            MenuItem("info", "Match & controls"),
            MenuItem("exit", if (net != null) "Leave match" else "Quit match", destructive = true),
        )
        ctx.ui.menu("Send Off · Level $levelNow", if (net == null) "The match keeps running" else null, items) { id ->
            when (id) {
                "sens" -> {
                    val steps = floatArrayOf(0.4f, 0.6f, 0.8f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f)
                    val idx = steps.indexOfFirst { it >= ctx.settings.lookSensitivity - 0.05f }.coerceAtLeast(0)
                    ctx.settings.lookSensitivity = steps[(idx + 1) % steps.size]
                    openPauseMenu()
                }
                "ads" -> {
                    val steps = floatArrayOf(0.4f, 0.55f, 0.7f, 0.85f, 1f, 1.15f, 1.35f)
                    val idx = steps.indexOfFirst { it >= adsSens - 0.03f }.coerceAtLeast(0)
                    adsSens = steps[(idx + 1) % steps.size]
                    ctx.settings.adsSensitivity = adsSens
                    openPauseMenu()
                }
                "turn" -> {
                    val steps = floatArrayOf(0f, 90f, 140f, 180f, 240f, 320f)
                    val idx = steps.indexOfFirst { it >= ctx.settings.turnSpeedLimit - 1f }.coerceAtLeast(0)
                    ctx.settings.turnSpeedLimit = steps[(idx + 1) % steps.size]
                    turnLimit = ctx.settings.turnSpeedLimit * MathX.DEG_TO_RAD
                    openPauseMenu()
                }
                "invert" -> {
                    invertY = !invertY
                    ctx.settings.invertLookY = invertY
                    openPauseMenu()
                }
                "studio" -> studio.open = true
                "info" -> ctx.ui.menu("Match & controls", modeBlurb(), listOf(MenuItem("back", "Back"))) { openPauseMenu() }
                "exit" -> {
                    ctx.storage.putInt("xp", xpTotal)
                    ctx.ui.exit(GameResult("Left Send Off", local?.kills?.toLong() ?: 0))
                }
            }
        }
    }

    /** One plain-language briefing per rules set — what this mode IS and how to play it. */
    private fun modeBlurb(): String = when (rules) {
        "tdm" -> "Team Deathmatch — first team to 40 eliminations wins. You respawn 4 s after death with 3 s of spawn protection. Friendly fire is off. Move: left stick · look: right thumb · fire, ADS, jump, crouch, reload: the round buttons."
        "gun" -> "Gun Game — every kill advances you to the next gun (pistol → SMG → rifle → shotgun → revolver → marksman → sniper → LMG → pan). Death drops you one stage. A kill with the final pan wins the match. No loot, fast respawns."
        "last_day" -> "Last Day Survival — no zone. Day turns to night while waves keep landing on the island. Survive as long as you can; every wave is worth XP."
        "lights_out" -> "Lights Out — permanent night with tight fog. Your muzzle flash is the light."
        "haunted" -> "Haunted — sick green fog and endless undead waves. Shoot what moves."
        else -> "Battle Royale — skydive, loot weapons and gear, stay inside the shrinking zone, be the last one standing. Move: left stick · look: right thumb · fire, ADS, jump, crouch, reload: the round buttons · pick up: the PICK button when it appears. Turning is rate-limited by the Turn limit in the pause menu, so the camera can never glitch around."
    }

    override fun onDestroy() {
        ctx.audio.stopMusic()
        ctx.storage.putInt("xp", xpTotal)
        ctx.storage.putInt("level", levelNow)
    }

    // ---------------------------------------------------------------- rendering

    override fun onRender(frame: Frame) {
        frame.clear(skyClear)
        frame.world(camera, environment = env) { r -> drawWorld(r) }
        frame.ui { hud -> drawHud(hud) }
    }

    private fun drawWorld(r: Renderer3D) {
        // Hand this frame's dynamic light (fire, sparks, magic) to the scene
        // and register the physics-driven particle systems.
        vfx.register(r, env)
        r.draw(map.terrainMesh, map.terrainMaterial, m.idt())
        r.draw(waterMesh, map.water, 0f, -0.15f, 0f)
        r.draw(map.buildingMesh, map.wallMaterial, m.idt())
        r.draw(map.roofMesh, map.roofMaterial, m.idt())
        r.draw(map.propMesh, map.propMaterial, m.idt())
        val far = camera.far
        for (t in map.trees) {
            val ddx = t.x - camera.position.x; val ddz = t.z - camera.position.z
            if (ddx * ddx + ddz * ddz > far * far) continue
            r.draw(ArenaMap.treeTrunk, map.trunk, t.x, t.y - 0.2f, t.z, 0f, t.scale)
            r.draw(map.crownMesh, map.leaves, t.x, t.y - 0.2f, t.z, 0f, t.scale, t.tint)
        }
        for (rk in map.rocks) r.draw(ArenaMap.rockMesh, map.rock, rk.x, rk.y + rk.sy * 0.2f, rk.z, rk.yaw, 0f, 0f, rk.sx, rk.sy * 1.2f, rk.sz)
        // Loot.
        val t = ctx.time.elapsed.toFloat()
        for (l in sim.loot) {
            if (l.taken) continue
            val ddx = l.x - camera.position.x; val ddz = l.z - camera.position.z
            if (ddx * ddx + ddz * ddz > 70f * 70f) continue
            val mat = lootMaterial(l.kind)
            val bob = sin(t * 2.5f + l.id) * 0.08f
            when (l.kind) {
                LootKind.WEAPON -> r.draw(gunMesh(Weapon.entries[l.value]), gunMat, l.x, l.y + 0.25f + bob, l.z, t * 1.2f + l.id, 1.3f)
                LootKind.AMMO -> r.draw(Mesh.unitCube, mat, l.x, l.y + 0.12f + bob, l.z, t + l.id, 0f, 0f, 0.35f, 0.24f, 0.25f)
                LootKind.MEDKIT -> r.draw(Mesh.unitCube, mat, l.x, l.y + 0.15f + bob, l.z, t + l.id, 0f, 0f, 0.4f, 0.28f, 0.28f)
                LootKind.ARMOR -> r.draw(Mesh.unitCube, mat, l.x, l.y + 0.3f + bob, l.z, t + l.id, 0f, 0f, 0.5f, 0.55f, 0.22f)
                LootKind.HELMET -> r.draw(Mesh.unitSphere, mat, l.x, l.y + 0.25f + bob, l.z, t + l.id, 0f, 0f, 0.42f, 0.3f, 0.42f)
            }
            r.blobShadow(l.x, l.y - 0.28f, l.z, 0.35f, 0.3f)
        }
        // Players.
        val alpha = ctx.time.alpha
        if (isAuthority) {
            for (p in sim.players.values) {
                if (p.id == localId && p.alive && adsOn && p.weapon.zoom > 2f) continue
                val pr = prev[p.id]
                val x = if (pr != null) MathX.lerp(pr[0], p.pos.x, alpha) else p.pos.x
                val y = if (pr != null) MathX.lerp(pr[1], p.pos.y, alpha) else p.pos.y
                val z = if (pr != null) MathX.lerp(pr[2], p.pos.z, alpha) else p.pos.z
                val flags = (if (p.alive) 1 else 0) or (if (p.crouching) 2 else 0) or (if (p.aiming) 4 else 0) or (if (p.skydiving) 8 else 0) or (if (p.parachute) 16 else 0)
                drawSoldier(r, x, y, z, if (p.id == localId) lookYaw else p.yaw, p.pitch, p.color, flags, p.weapon, p.armor, p.helmet, p.ctrl.position.dstXZ(tmp.set(pr?.get(0) ?: x, 0f, pr?.get(2) ?: z)) * 0f + walkPhase(p), walkSpeeds[p.id] ?: 0f, team = p.team, id = p.id,
                    fx = if (p.id == localId) myFx else actorFx.getOrPut(p.id) { CharFx() })
            }
        } else {
            val me = local
            if (me != null && !(me.alive && adsOn && me.weapon.zoom > 2f)) {
                val (x, y, z) = viewTarget().takeIf { me.alive } ?: Triple(me.pos.x, me.pos.y, me.pos.z)
                val flags = (if (me.alive) 1 else 0) or (if (crouchOn) 2 else 0) or (if (adsOn) 4 else 0) or (if (me.skydiving) 8 else 0) or (if (me.parachute) 16 else 0)
                drawSoldier(r, x, y, z, lookYaw, lookPitch, me.color, flags, me.weapon, me.armor, me.helmet, walkPhase(me), walkSpeeds[me.id] ?: 0f, team = me.team, id = me.id, fx = myFx)
            }
            for (a in actors.values) {
                if (a.id == localId || a.buffer.isEmpty) continue
                val v = a.view
                drawSoldier(r, v[0], v[1], v[2], v[3], v[4], a.color, a.flags, Weapon.entries.getOrElse(a.weapon) { Weapon.PAN }, a.armor, a.helmet, a.walk, a.speed01, team = a.team, id = a.id, fx = a.fx)
            }
        }
        // Zone wall (absent in survival rules — there is no shrinking zone to show).
        if (sim.zone.enabled) {
            val z = sim.zone
            r.draw(Mesh.unitCylinder, zoneMat, z.cx, -20f, z.cz, 0f, 0f, 0f, z.radius * 2f, 140f, z.radius * 2f)
        }
        for (tr in tracers) r.drawLine(tr[0], tr[1], tr[2], tr[3], tr[4], tr[5], 0.025f, tracerMat, Colors.withAlpha(Colors.WHITE, 1f - tr[6] / 0.09f))
        r.particles(puffs)
        r.particles(sparks)
    }

    private val walkPhases = HashMap<Int, FloatArray>()
    private val walkSpeeds = HashMap<Int, Float>()
    private fun walkPhase(p: ArenaPlayer): Float {
        val st = walkPhases.getOrPut(p.id) { floatArrayOf(p.pos.x, p.pos.z, 0f) }
        val moved = MathX.length(p.pos.x - st[0], p.pos.z - st[1])
        walkSpeeds[p.id] = MathX.clamp01(moved * 28f)
        st[0] = p.pos.x; st[1] = p.pos.z
        st[2] += moved * 2.2f
        if (p.id == localId && moved > 0.04f && p.ctrl.grounded && (st[2] % MathX.PI_F) < moved * 2.2f) ctx.audio.play(sStep, 0.35f)
        return st[2]
    }

    private fun lootMaterial(kind: LootKind) = lootGlow.getOrPut(kind) {
        val c = when (kind) {
            LootKind.WEAPON -> 0xFFB0BEC5.toInt(); LootKind.AMMO -> 0xFFFFC107.toInt(); LootKind.MEDKIT -> 0xFFEF5350.toInt()
            LootKind.ARMOR -> 0xFF42A5F5.toInt(); LootKind.HELMET -> 0xFF7E57C2.toInt()
        }
        Material(color = c, emissive = c, emissiveStrength = 0.6f, specular = 0.6f)
    }

    private fun gunMesh(w: Weapon): Mesh = gunMeshes.getOrPut(w) {
        val mb = MeshBuilder()
        when (w) {
            Weapon.PAN -> { mb.cylinder(0f, 0f, 0.25f, 0.2f, 0.06f, 16); mb.box(0f, 0.03f, -0.05f, 0.05f, 0.04f, 0.3f) }
            Weapon.PISTOL -> { mb.box(0f, 0f, 0.08f, 0.06f, 0.08f, 0.24f); mb.box(0f, -0.08f, 0f, 0.05f, 0.12f, 0.07f) }
            Weapon.SMG -> { mb.box(0f, 0f, 0.12f, 0.08f, 0.12f, 0.42f); mb.box(0f, -0.12f, 0.12f, 0.05f, 0.16f, 0.06f); mb.box(0f, -0.06f, -0.12f, 0.06f, 0.1f, 0.16f) }
            Weapon.RIFLE -> { mb.box(0f, 0f, 0.2f, 0.08f, 0.12f, 0.7f); mb.withTransform(Mat4().setTRS(0f, 0.01f, 0.55f, pitch = MathX.HALF_PI)) { cylinder(0f, 0f, 0f, 0.025f, 0.35f, 8) }
                mb.box(0f, -0.13f, 0.25f, 0.06f, 0.2f, 0.08f); mb.box(0f, -0.04f, -0.2f, 0.07f, 0.14f, 0.24f) }
            Weapon.SHOTGUN -> { mb.box(0f, 0f, 0.25f, 0.09f, 0.1f, 0.8f); mb.box(0f, -0.08f, 0.32f, 0.08f, 0.07f, 0.3f); mb.box(0f, -0.05f, -0.2f, 0.07f, 0.14f, 0.26f) }
            Weapon.SNIPER -> { mb.box(0f, 0f, 0.3f, 0.07f, 0.1f, 1.0f); mb.withTransform(Mat4().setTRS(0f, 0.1f, 0.25f, pitch = MathX.HALF_PI)) { cylinder(0f, -0.15f, 0f, 0.04f, 0.3f, 10) }
                mb.box(0f, -0.05f, -0.25f, 0.07f, 0.15f, 0.3f) }
            Weapon.REVOLVER -> { mb.box(0f, 0f, 0.05f, 0.055f, 0.09f, 0.26f); mb.withTransform(Mat4().setTRS(0f, 0.02f, 0.12f, pitch = MathX.HALF_PI)) { cylinder(0f, 0f, 0f, 0.05f, 0.1f, 10) }
                mb.box(0f, -0.09f, -0.02f, 0.045f, 0.12f, 0.07f); mb.box(0f, 0.02f, 0.22f, 0.03f, 0.03f, 0.12f) }
            Weapon.MARKSMAN -> { mb.box(0f, 0f, 0.18f, 0.07f, 0.11f, 0.62f); mb.withTransform(Mat4().setTRS(0f, 0.01f, 0.5f, pitch = MathX.HALF_PI)) { cylinder(0f, 0f, 0f, 0.022f, 0.3f, 8) }
                mb.withTransform(Mat4().setTRS(0f, 0.12f, 0.12f, pitch = MathX.HALF_PI)) { cylinder(0f, -0.12f, 0f, 0.032f, 0.22f, 10) }
                mb.box(0f, -0.13f, 0.22f, 0.06f, 0.2f, 0.08f); mb.box(0f, -0.05f, -0.22f, 0.07f, 0.15f, 0.28f) }
            Weapon.LMG -> { mb.box(0f, 0f, 0.15f, 0.09f, 0.13f, 0.62f); mb.withTransform(Mat4().setTRS(0f, 0.01f, 0.52f, pitch = MathX.HALF_PI)) { cylinder(0f, 0f, 0f, 0.028f, 0.32f, 8) }
                mb.box(0f, -0.14f, 0.1f, 0.08f, 0.12f, 0.16f); mb.box(0f, -0.08f, -0.08f, 0.1f, 0.1f, 0.22f)
                mb.box(0f, -0.05f, -0.26f, 0.07f, 0.14f, 0.2f); mb.box(0f, 0.12f, 0.02f, 0.05f, 0.05f, 0.2f) }
        }
        mb.build()
    }

    private fun shirt(color: Int) = shirts.getOrPut(color) { Material(color = color, specular = 0.25f, rim = 0.2f) }

    /** One soldier rig per (colour, body variant); [rigPose] is reused for every draw. */
    private val soldierRigs = HashMap<Long, CharRig>()
    private val hairMats = HashMap<Int, Material>()
    private val rigPose = CharRig.Pose()
    private val boots = Material(color = 0xFF1B1E24.toInt(), specular = 0.3f)
    private val packMat = Material(color = 0xFF3E4A32.toInt(), specular = 0.25f)

    private fun hairMat(id: Int) = hairMats.getOrPut(id % 4) {
        val c = intArrayOf(0xFF2E2118.toInt(), 0xFF4A3220.toInt(), 0xFF6B4A2E.toInt(), 0xFF1E1A16.toInt())[id % 4]
        Material(color = c, specular = 0.35f, shininess = 40f)
    }

    /** People differ: some rigs are feminine, some carry packs, hair varies. */
    private fun rigFor(color: Int, id: Int): CharRig {
        // The local player is the Character Studio's live model.
        if (id == localId) studioRig?.let { return it }
        val key = (color.toLong() shl 32) or (id.toLong() and 0xFFFFFFFFL)
        return soldierRigs.getOrPut(key) {
            val feminine = id % 3 == 2
            CharRig(
                body = shirt(color), pants = pants, skin = skin, shoes = boots,
                accent = shirt(Colors.scale(color, 0.55f)),
                hair = hairMat(id),
                hairStyle = if (feminine) (1 + id % 2) else 0,
                feminine = feminine,
                backpack = if (id % 2 == 0) packMat else null,
            )
        }
    }

    /** Soldier on the shared human rig: real joints, run cycle, gear, weapon in the hands. */
    private fun drawSoldier(r: Renderer3D, x: Float, y: Float, z: Float, yaw: Float, pitch: Float, color: Int, flags: Int, weapon: Weapon, armor: Int, helmet: Int, walk: Float, speed01: Float = 0f, team: Int = 0, id: Int = 0, fx: CharFx? = null) {
        val alive = flags and 1 != 0
        val crouch = flags and 2 != 0
        val sky = flags and 8 != 0
        val chute = flags and 16 != 0
        // Teams wear their colours; free-for-all keeps the player's own.
        val kit = if (sim.teams && team in TEAM_COLORS.indices) TEAM_COLORS[team] else color
        val body = shirt(kit)
        val vscale = 0.95f + (id % 5) * 0.025f
        if (!alive) {
            r.draw(Mesh.unitCube, body, x, y + 0.15f, z, yaw, MathX.HALF_PI, 0f, 0.5f, 0.6f, 0.28f)
            r.draw(Mesh.unitCube, pants, x, y + 0.12f, z - cos(yaw) * 0.0f, yaw, MathX.HALF_PI, 0f, 0.42f, 1.4f, 0.2f)
            return
        }
        if (sky && !chute) {
            // Free fall: the rig in its airborne pose, limbs out.
            rigPose.phase = walk * 1.15f
            rigPose.speed = 0f
            rigPose.crouch = false
            rigPose.airborne = true
            rigPose.aimHold = false
            rigPose.lean = 0f
            rigPose.detail = true
            rigFor(kit, id).draw(r, x, y, z, yaw, rigPose, vscale, fx)
            return
        }
        if (chute) {
            r.draw(canopyMesh, canopyMat, x, y + 4.2f, z, yaw, 1f)
            r.drawLine(x - 0.2f, y + 1.6f, z, x - 2.2f, y + 4.2f, z, 0.012f, gunMat)
            r.drawLine(x + 0.2f, y + 1.6f, z, x + 2.2f, y + 4.2f, z, 0.012f, gunMat)
        }
        // Soldier on the shared character rig: smooth limbs with real joints,
        // arms raised onto the weapon, run cycle when moving.
        rigPose.phase = walk * 1.15f
        rigPose.speed = if (chute) 0f else speed01
        rigPose.crouch = crouch
        rigPose.airborne = chute
        rigPose.aimHold = !chute
        rigPose.aimPitch = MathX.clamp(pitch, -0.9f, 0.9f)
        rigPose.lean = 0f
        rigPose.detail = true
        rigFor(kit, id).draw(r, x, y, z, yaw, rigPose, vscale, fx)
        val hip = y + (if (crouch) 0.55f else 0.9f) * vscale
        val torsoY = hip + 0.36f * vscale
        // Gear on top of the rig: plate carrier, helmet, weapon in the hands.
        if (armor > 0) r.draw(Mesh.unitCube, vestMat, x, torsoY + 0.02f, z, yaw, 0f, 0f, 0.54f, 0.46f, 0.33f)
        val headY = torsoY + 0.50f * vscale
        if (helmet > 0 || sim.teams) r.draw(Mesh.unitSphere, helmetMat, x, headY + 0.05f, z, yaw, 0f, 0f, 0.30f, 0.24f, 0.30f)
        // The weapon itself, held level with the aim just off the chest.
        val forwardX = sin(yaw); val forwardZ = cos(yaw)
        val rightX = -forwardZ; val rightZ = forwardX
        val gunPitch = MathX.clamp(pitch, -0.9f, 0.9f)
        val sx = x + rightX * 0.22f + forwardX * 0.25f; val sz = z + rightZ * 0.22f + forwardZ * 0.25f
        m.setTRS(sx, torsoY + 0.18f, sz, yaw, gunPitch, 0f)
        m.translate(0f, 0f, 0.18f)
        val gm = m.copy()
        r.draw(gunMesh(weapon), gunMat, gm.translate(-0.05f, 0.02f, 0.12f))
        if (!r.shadowMapsActive) r.blobShadow(x, map.physics.groundHeight(x, z, y + 0.5f), z, 0.45f, 0.35f)
    }

    // ---------------------------------------------------------------- HUD

    private fun drawHud(hud: Hud) {
        hud.accent = ACCENT
        val d = hud.density
        val me = local ?: return
        val w = hud.width; val h = hud.height
        if (studio.open) {
            // The Character Studio owns the screen; the match renders behind it.
            studio.drawOverlay(hud)
            return
        }
        val ads = adsOn && me.alive && me.weapon.zoom > 2f
        if (ads) drawScope(hud)
        // Damage vignette.
        if (damageFrom > 0f) {
            val rel = MathX.angleDelta(lookYaw, damageAngle)
            val cx = w / 2f; val cy = h / 2f
            hud.batch.arc(cx, cy, min(w, h) * 0.28f, 10f * d, -rel - 0.35f, 0.7f, Colors.withAlpha(0xFFFF3B30.toInt(), min(1f, damageFrom)))
        }
        if (me.health < 30f && me.alive) hud.batch.rect(0f, 0f, w, h, Colors.withAlpha(0xFFB00020.toInt(), 0.12f + 0.06f * sin(ctx.time.elapsed.toFloat() * 6f)))
        if (me.alive && !matchOver) {
            drawCrosshair(hud, me)
            drawControls(hud, me)
        }
        drawTopBar(hud, me)
        drawMinimap(hud, me)
        drawKillFeed(hud)
        drawStatus(hud, me)
        for (n in damageNumbers) {
            tmp.set(n[0], n[1], n[2])
            if (camera.worldToScreen(tmp, w, h, tmp)) {
                val a = 1f - n[4] / 0.9f
                hud.text("${n[3].toInt()}", tmp.x, tmp.y, if (n[5] > 0f) 20f else 16f, Colors.withAlpha(if (n[5] > 0f) 0xFFFFD54F.toInt() else Colors.WHITE, a), Align.CENTER, bold = true)
            }
        }
        if (System.currentTimeMillis() < hintUntil && me.skydiving) {
            hud.text("Steer your landing with the stick · look with your right thumb", w / 2f, h * 0.28f, 15f, Colors.WHITE, Align.CENTER, bold = true)
        }
        if (matchOver || (!me.alive && !sim.respawnMode)) drawEndPanel(hud, me)
        else if (!me.alive) drawRespawnOverlay(hud)
    }

    /** Respawn modes: a clean countdown instead of a death screen. */
    private fun drawRespawnOverlay(hud: Hud) {
        val d = hud.density
        val w = hud.width; val h = hud.height
        hud.dim(0.35f)
        val pw = 300f * d; val ph = 110f * d
        val px = (w - pw) / 2f; val py = h * 0.42f - ph / 2f
        hud.solidPanel(px, py, pw, ph, 0xE6141922.toInt(), 20f)
        val secs = max(0f, respawnCountdown).toInt() + 1
        hud.text(if (respawnCountdown > 0f) "Respawning in $secs" else "Returning to the fight…",
            w / 2f, py + 24f * d, 20f, Colors.WHITE, Align.CENTER, bold = true, shadow = false)
        if (respawnCountdown > 0f) {
            val total = if (sim.gunGame) 1.5f else 4f
            hud.bar(px + 30f * d, py + 64f * d, pw - 60f * d, 8f * d, MathX.clamp01(1f - respawnCountdown / total), ACCENT)
        }
    }

    private fun drawCrosshair(hud: Hud, me: ArenaPlayer) {
        val d = hud.density
        val cx = hud.width / 2f; val cy = hud.height / 2f
        val gap = (6f + me.spread * 260f) * d
        val len = 7f * d
        val col = if (hitMarker > 0f) (if (hitMarkerKill) 0xFFFF3B30.toInt() else Colors.WHITE) else 0xE6FFFFFF.toInt()
        val b = hud.batch
        b.line(cx - gap - len, cy, cx - gap, cy, 2f * d, col); b.line(cx + gap, cy, cx + gap + len, cy, 2f * d, col)
        b.line(cx, cy - gap - len, cx, cy - gap, 2f * d, col); b.line(cx, cy + gap, cx, cy + gap + len, 2f * d, col)
        b.circle(cx, cy, 1.6f * d, col)
        if (hitMarker > 0f) {
            val s = 9f * d
            b.line(cx - s, cy - s, cx - s / 2f, cy - s / 2f, 2.2f * d, col); b.line(cx + s, cy - s, cx + s / 2f, cy - s / 2f, 2.2f * d, col)
            b.line(cx - s, cy + s, cx - s / 2f, cy + s / 2f, 2.2f * d, col); b.line(cx + s, cy + s, cx + s / 2f, cy + s / 2f, 2.2f * d, col)
        }
    }

    private fun drawScope(hud: Hud) {
        val w = hud.width; val h = hud.height
        val r = min(w, h) * 0.46f
        val cx = w / 2f; val cy = h / 2f
        val b = hud.batch
        b.rect(0f, 0f, cx - r, h, 0xFF000000.toInt()); b.rect(cx + r, 0f, w - cx - r, h, 0xFF000000.toInt())
        b.ring(cx, cy, r + 40f, 80f, 0xFF000000.toInt())
        b.line(cx - r, cy, cx + r, cy, 1.5f, 0xCC000000.toInt()); b.line(cx, cy - r, cx, cy + r, 1.5f, 0xCC000000.toInt())
    }

    private fun drawControls(hud: Hud, me: ArenaPlayer) {
        val b = hud.batch
        joystick.draw(b, ACCENT)
        adsBtn.highlight = adsOn; crouchBtn.highlight = crouchOn
        reloadBtn.progress = if (me.reloadLeft > 0f) 1f - me.reloadLeft / me.weapon.reload.coerceAtLeast(0.1f) else -1f
        healBtn.label = "+${me.medkits}"
        for (btn in listOf(fire, fire2, adsBtn, jumpBtn, crouchBtn, reloadBtn, healBtn, pickupBtn)) btn.draw(b, hud.fontBold, hud.density)
        // Weapon slots (tap to switch).
        val d = hud.density
        val sw = 112f * d; val sh = 46f * d
        val sy = hud.height - hud.safeBottom - sh - 12f * d
        for (i in 0..1) {
            val sx = hud.width / 2f - sw - 6f * d + i * (sw + 12f * d)
            val active = me.active == i
            val wpn = me.slots[i]
            hud.panel(sx, sy, sw, sh, if (active) 0xE62A3646.toInt() else 0x99141820.toInt(), 12f)
            if (active) hud.batch.roundRect(sx, sy + sh - 3f * d, sw, 3f * d, 1.5f * d, ACCENT)
            hud.text(wpn.label, sx + 10f * d, sy + 6f * d, 12f, Colors.WHITE, bold = true, shadow = false)
            val ammoText = if (wpn == Weapon.PAN) "melee" else "${me.mags[i]} / ${me.ammo[wpn.ammo]}"
            hud.text(ammoText, sx + 10f * d, sy + 24f * d, 13f, if (wpn != Weapon.PAN && me.mags[i] == 0) 0xFFFF6B6B.toInt() else 0xCCFFFFFF.toInt(), shadow = false)
            if (hud.hotspot("slot$i", sx, sy, sw, sh)) slotLatch = i
        }
        // Health and armour.
        val bw = 240f * d
        val bx = hud.width / 2f - bw / 2f
        val by = sy - 22f * d
        hud.bar(bx, by, bw, 9f * d, me.health / 100f, if (me.health < 30f) 0xFFFF5252.toInt() else 0xFFF1F3F5.toInt())
        if (me.armor > 0) hud.text("Vest ${me.armor}", bx - 8f * d, by - 4f * d, 11f, 0xFF90CAF9.toInt(), Align.RIGHT)
        if (me.helmet > 0) hud.text("Helmet ${me.helmet}", bx + bw + 8f * d, by - 4f * d, 11f, 0xFFB39DDB.toInt())
    }

    private fun drawTopBar(hud: Hud, me: ArenaPlayer) {
        val d = hud.density
        val alive = if (isAuthority) sim.aliveCount else actors.values.count { it.alive }
        val x = hud.safeLeft + 14f * d
        val y = hud.safeTop + 10f * d
        // Mode card: the scoreboard this mode actually runs on.
        when {
            sim.teams -> {
                hud.panel(x, y, 190f * d, 46f * d, 0x99101418.toInt(), 12f)
                hud.text("TEAM DEATHMATCH", x + 12f * d, y + 8f * d, 10.5f, 0xAAFFFFFF.toInt(), bold = true, shadow = false)
                hud.text("${sim.teamScore[0]}", x + 12f * d, y + 22f * d, 17f, 0xFF64B5F6.toInt(), bold = true, shadow = false)
                hud.text("${sim.teamScore[1]}", x + 58f * d, y + 22f * d, 17f, 0xFFFFB74D.toInt(), bold = true, shadow = false)
                hud.text("first to 40", x + 104f * d, y + 27f * d, 10.5f, 0x88FFFFFF.toInt(), shadow = false)
            }
            sim.gunGame -> {
                hud.panel(x, y, 190f * d, 46f * d, 0x99101418.toInt(), 12f)
                hud.text("GUN GAME", x + 12f * d, y + 8f * d, 10.5f, 0xAAFFFFFF.toInt(), bold = true, shadow = false)
                val stage = me.gunLevel.coerceIn(0, GUN_LADDER.size - 1)
                hud.text("Stage ${stage + 1} / ${GUN_LADDER.size}", x + 12f * d, y + 22f * d, 15f, 0xFFFFD27A.toInt(), bold = true, shadow = false)
                hud.text(GUN_LADDER[stage].label, x + 112f * d, y + 25f * d, 11f, 0xCCFFFFFF.toInt(), shadow = false)
            }
            sim.survival -> {
                hud.panel(x, y, 170f * d, 46f * d, 0x99101418.toInt(), 12f)
                hud.text(if (rules == "haunted") "HAUNTED" else if (rules == "lights_out") "LIGHTS OUT" else "LAST DAY SURVIVAL",
                    x + 12f * d, y + 8f * d, 10.5f, 0xAAFFFFFF.toInt(), bold = true, shadow = false)
                hud.text("Wave $waveNumber", x + 12f * d, y + 22f * d, 16f, 0xFF7ED957.toInt(), bold = true, shadow = false)
                hud.text("$alive alive", x + 96f * d, y + 25f * d, 11f, 0xCCFFFFFF.toInt(), shadow = false)
            }
            else -> {
                hud.panel(x, y, 150f * d, 40f * d, 0x99101418.toInt(), 12f)
                hud.text("$alive alive", x + 12f * d, y + 10f * d, 15f, Colors.WHITE, bold = true, shadow = false)
                hud.text("${me.kills} ◎", x + 138f * d, y + 10f * d, 15f, 0xFFFFD27A.toInt(), Align.RIGHT, bold = true, shadow = false)
            }
        }
        // Level chip: progression always visible, not only at the end.
        val ly = y + if (sim.teams || sim.gunGame || sim.survival) 52f * d else 44f * d
        hud.panel(x, ly, 92f * d, 22f * d, 0x80101418.toInt(), 8f)
        hud.text("Lvl $levelNow", x + 10f * d, ly + 4f * d, 11.5f, 0xFFFFD54F.toInt(), bold = true, shadow = false)
        val prevXp = xpForLevel(levelNow)
        val nextXp = xpForLevel(levelNow + 1).coerceAtLeast(prevXp + 1)
        hud.bar(x + 48f * d, ly + 8f * d, 36f * d, 5f * d, MathX.clamp01((xpTotal - prevXp) / (nextXp - prevXp).toFloat()), 0xFFFFD54F.toInt())
        if (sim.teams || sim.gunGame) hud.text("${me.kills} ◎", x + 110f * d, ly + 4f * d, 11.5f, 0xFFFFD27A.toInt(), shadow = false)
        // Zone clock (battle royale only — the other modes have no zone).
        val z = sim.zone
        if (z.enabled) {
            val t = max(0f, z.timer).toInt()
            hud.text(if (z.shrinking) "Zone closing · ${t}s" else "Zone shrinks in ${t / 60}:${String.format(java.util.Locale.US, "%02d", t % 60)}",
                x + 4f * d, ly + 28f * d, 13f, if (z.shrinking) 0xFF64B5F6.toInt() else 0xDDFFFFFF.toInt())
            if (me.alive && z.outside(me.pos.x, me.pos.z) && !me.skydiving) hud.text("Outside the zone!", hud.width / 2f, y + 60f * d, 18f, 0xFF64B5F6.toInt(), Align.CENTER, bold = true)
        }
        if (me.healProgress > 0f) {
            hud.text("Healing…", hud.width / 2f, hud.height * 0.6f, 15f, Colors.WHITE, Align.CENTER, bold = true)
            hud.bar(hud.width / 2f - 70f * d, hud.height * 0.6f + 24f * d, 140f * d, 6f * d, me.healProgress, 0xFF66BB6A.toInt())
        }
        if (me.reloadLeft > 0f) hud.text("Reloading", hud.width / 2f, hud.height * 0.56f, 14f, 0xDDFFFFFF.toInt(), Align.CENTER)
        if (net == null && hud.iconButton("menu", "☰", hud.width - hud.safeRight - 150f * d, y + 20f * d, 18f)) onBack()
    }

    private fun drawMinimap(hud: Hud, me: ArenaPlayer) {
        val d = hud.density
        val size = 118f * d
        val x = hud.width - hud.safeRight - size - 12f * d
        val y = hud.safeTop + 10f * d
        val b = hud.batch
        b.roundRect(x - 3f * d, y - 3f * d, size + 6f * d, size + 6f * d, 14f * d, 0xAA0E1116.toInt())
        val scale = size / (ArenaMap.SIZE * 0.95f)
        val cx = x + size / 2f; val cy = y + size / 2f
        fun mx(wx: Float) = cx - wx * scale
        fun my(wz: Float) = cy - wz * scale
        b.circle(cx, cy, ArenaMap.SIZE * 0.47f * scale, 0xFF5E8F3E.toInt())
        for (t in map.towns) b.circle(mx(t[0]), my(t[1]), t[2] * 0.6f * scale, 0x55D9C79A)
        val z = sim.zone
        if (z.enabled) {
            b.ring(mx(z.cx), my(z.cz), z.radius * scale, 1.6f * d, Colors.WHITE)
            b.ring(mx(z.toX), my(z.toZ), z.toR * scale, 1.2f * d, 0xFF64B5F6.toInt())
        }
        // Teammates as dots; enemies never show on the minimap.
        if (sim.teams) {
            val dot = if (me.team == 1) 0xFFFFB74D.toInt() else 0xFF64B5F6.toInt()
            if (isAuthority) {
                for (p in sim.players.values) {
                    if (p.id == me.id || !p.alive || p.team != me.team) continue
                    b.circle(mx(p.pos.x), my(p.pos.z), 3.2f * d, dot)
                }
            } else {
                for (a in actors.values) {
                    if (a.id == localId || !a.alive || a.team != me.team) continue
                    b.circle(mx(a.view[0]), my(a.view[2]), 3.2f * d, dot)
                }
            }
        }
        val px = mx(me.pos.x); val py = my(me.pos.z)
        val dirx = -sin(lookYaw); val diry = -cos(lookYaw)
        b.polygon(floatArrayOf(px + dirx * 8f * d, py + diry * 8f * d, px - diry * 4f * d - dirx * 4f * d, py + dirx * 4f * d - diry * 4f * d,
            px + diry * 4f * d - dirx * 4f * d, py - dirx * 4f * d - diry * 4f * d), ACCENT)
    }

    private fun drawKillFeed(hud: Hud) {
        val d = hud.density
        val now = System.currentTimeMillis()
        var y = hud.safeTop + 142f * d
        val x = hud.width - hud.safeRight - 14f * d
        for ((text, at) in killFeed) {
            if (now - at > 6000) continue
            val a = 1f - MathX.clamp01((now - at - 5000) / 1000f)
            val tw = hud.measure(text, 12f) + 16f * d
            hud.panel(x - tw, y, tw, 22f * d, Colors.withAlpha(0xFF101418.toInt(), 0.65f * a), 8f)
            hud.text(text, x - 8f * d, y + 4f * d, 12f, Colors.withAlpha(Colors.WHITE, a), Align.RIGHT, shadow = false)
            y += 26f * d
        }
    }

    private fun drawStatus(hud: Hud, me: ArenaPlayer) {
        if (!me.skydiving || !me.alive) return
        val d = hud.density
        val ground = map.physics.groundHeight(me.pos.x, me.pos.z, me.pos.y)
        hud.text(if (me.parachute) "Parachute open · ${(me.pos.y - ground).toInt()} m" else "Free fall · ${(me.pos.y - ground).toInt()} m",
            hud.width / 2f, hud.safeTop + 14f * d, 16f, Colors.WHITE, Align.CENTER, bold = true)
    }

    private fun drawEndPanel(hud: Hud, me: ArenaPlayer) {
        val d = hud.density
        val won = when {
            matchOver && sim.teams -> sim.teamScore[me.team] >= sim.teamScore[1 - me.team] && sim.teamScore[me.team] >= 40
            else -> matchOver && me.alive && (winnerName == me.name || me.place == 1)
        }
        val w = min(hud.width - 40f * d, 380f * d)
        val h = 200f * d
        val x = (hud.width - w) / 2f
        val y = hud.height * 0.5f - h / 2f
        hud.solidPanel(x, y, w, h, 0xE6141922.toInt(), 24f)
        val title = when {
            won -> "VICTORY"
            matchOver && sim.teams -> (if (sim.teamScore[0] > sim.teamScore[1]) "Blue" else "Orange") + " team wins"
            matchOver -> "${winnerName ?: "Nobody"} wins"
            else -> "Eliminated"
        }
        hud.text(title, x + w / 2f, y + 18f * d, 28f, if (won) 0xFFFFD54F.toInt() else Colors.WHITE, Align.CENTER, bold = true, shadow = false)
        val place = if (won) 1 else me.place.coerceAtLeast(1)
        hud.text("#$place · ${me.kills} kills${if (!me.alive && me.killedBy >= 0) " · by ${nameOfId(me.killedBy)}" else ""}", x + w / 2f, y + 62f * d, 15f, 0xCCFFFFFF.toInt(), Align.CENTER, shadow = false)
        if (xpEarned > 0) {
            val gained = xpForLevel(levelNow + 1) - xpForLevel(levelNow)
            val into = (xpTotal - xpForLevel(levelNow)).coerceIn(0, gained)
            hud.text("+$xpEarned XP · Level $levelNow", x + w / 2f, y + 88f * d, 13.5f, 0xFFFFD54F.toInt(), Align.CENTER, bold = true, shadow = false)
            hud.bar(x + w / 2f - 70f * d, y + 108f * d, 140f * d, 5f * d, MathX.clamp01(into / gained.toFloat().coerceAtLeast(1f)), 0xFFFFD54F.toInt())
        }
        val bw = (w - 48f * d) / 2f
        if (!matchOver && hud.button("spectate", "Spectate", x + 16f * d, y + 120f * d, bw, 46f * d, ButtonStyle.SECONDARY)) {
            val ids = if (isAuthority) sim.players.values.filter { it.alive }.map { it.id } else actors.values.filter { it.alive && it.id != localId }.map { it.id }
            if (ids.isNotEmpty()) spectate = ids[(ids.indexOf(spectate) + 1).mod(ids.size)]
        }
        if (matchOver && net?.isHost == true && hud.button("lobby", "Back to lobby", x + 16f * d, y + 120f * d, bw, 46f * d)) net?.endMatch()
        if (matchOver && net == null && hud.button("again", "Play again", x + 16f * d, y + 120f * d, bw, 46f * d)) ctx.ui.exit(GameResult("Again"))
        if (hud.button("exit", "Exit", x + 32f * d + bw, y + 120f * d, bw, 46f * d, ButtonStyle.GHOST)) ctx.ui.exit(GameResult(title, me.kills.toLong(), won))
    }

    companion object {
        const val ID = "arena"
        private val ACCENT = 0xFFFF8A3D.toInt()
        /** Team Deathmatch kit colours: blue vs orange. */
        private val TEAM_COLORS = intArrayOf(0xFF2F6FB2.toInt(), 0xFFE07A2F.toInt())

        /** Keep camera yaw bounded during long sessions and extreme touch drags. */
        private fun wrapYaw(angle: Float): Float {
            var wrapped = angle % MathX.TAU
            if (wrapped > MathX.PI_F) wrapped -= MathX.TAU
            if (wrapped < -MathX.PI_F) wrapped += MathX.TAU
            return wrapped
        }
        private const val MSG_CMD = 32
        private const val MSG_SNAP = 33
        private const val EV_HIT = 40
        private const val EV_KILL = 41
        private const val EV_LOOT_TAKEN = 42
        private const val EV_LOOT_SPAWN = 43
        private const val EV_OVER = 44
        private const val EV_ROSTER = 45
        private val BOT_NAMES = listOf("Raven", "Kodo", "Viper", "Nova", "Blaze", "Echo", "Titan", "Shade", "Rook", "Jinx", "Atlas", "Mira", "Onyx", "Zed", "Kira")
        private val EngineColors = com.oea.engine.EngineSettings.PLAYER_COLORS

        val descriptor = GameDescriptor(
            id = ID,
            title = "Send Off",
            tagline = "Battle royale · team deathmatch · gun game",
            description = "A competitive shooter with real modes, not labels. Battle Royale: skydive, loot pistols, SMGs, rifles, shotguns, revolvers, marksman rifles, LMGs and snipers, stay inside the shrinking zone, be last standing. " +
                "Team Deathmatch: two teams, respawns, spawn protection, first to 40 eliminations. Gun Game: every kill advances your gun — a kill with the final pan wins. " +
                "Survival: Last Day (day turns to night while waves land), Lights Out (permanent night) and Haunted (green fog, endless undead). " +
                "Three island levels — Green Island, snow-capped Highlands and red-ridge Badlands — each with its own landform, palette, roads and vegetation. " +
                "Twin-stick touch, gamepad and keyboard; aim-down-sights, crouch, sprint and per-weapon handling. " +
                "Look sensitivity, ADS sensitivity, invert-Y and a turn-speed limit live in the pause menu and change the camera for real — the turn limit is what keeps the view glitch-free. " +
                "Open Character Studio from Pause to reshape gender/build, facial structure, skin, hair and layered clothing; the playable mesh updates on the turntable in real time and the saved character returns next match. " +
                "Fight bots alone or friends over Wi-Fi, hotspot or online.",
            accent = ACCENT,
            glyph = "◎",
            orientation = GameOrientation.LANDSCAPE,
            dimension = GameDimension.D3,
            minPlayers = 1,
            maxPlayers = 12,
            modes = setOf(PlayMode.SOLO, PlayMode.LAN, PlayMode.ONLINE),
            options = listOf(
                GameOption("rules", "Game mode", listOf(
                    Choice("br", "Battle Royale", "Zone, loot, last one standing"),
                    Choice("tdm", "Team Deathmatch", "Two teams · respawns · first to 40"),
                    Choice("gun", "Gun Game", "One kill = next gun · pan finish"),
                    Choice("last_day", "Last Day Survival", "Day turns to night · endless waves"),
                    Choice("lights_out", "Lights Out", "Permanent night · tight fog"),
                    Choice("haunted", "Haunted", "Green fog · endless undead"),
                ), "br"),
                GameOption("bots", "Bots", listOf(Choice("0", "None"), Choice("5", "5 bots"), Choice("9", "9 bots"), Choice("15", "15 bots")), "9"),
                GameOption("difficulty", "Bot level", listOf(Choice("easy", "Recruit"), Choice("normal", "Regular"), Choice("hard", "Veteran")), "normal"),
                GameOption("zone", "Zone speed", listOf(Choice("normal", "Normal"), Choice("fast", "Fast")), "normal"),
                GameOption("terrain", "Island level", listOf(
                    Choice("island", "Green island", "Rolling hills and broadleaf woods"),
                    Choice("highlands", "Highlands", "Alpine ridges, snowcaps, pines"),
                    Choice("badlands", "Badlands", "Red rock ridges, dry scrub, dust"),
                ), "island"),
                GameOption("loadout", "Weapon pool", listOf(
                    Choice("all", "All weapons"),
                    Choice("rifles", "Rifles & autos"),
                    Choice("snipers", "Long range"),
                    Choice("shotguns", "Close quarters"),
                    Choice("close", "Pistols & SMGs"),
                ), "all"),
            ),
            preferredIsland = IslandStyle.CARD,
            version = 2,
            factory = { OeaArena() },
        )
    }

    @Suppress("unused") private fun unusedAbs() = abs(0f)
}
