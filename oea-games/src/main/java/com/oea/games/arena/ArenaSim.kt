package com.oea.games.arena

import com.oea.engine.math.Intersect
import com.oea.engine.math.MathX
import com.oea.engine.math.Rng
import com.oea.engine.math.Vec3
import com.oea.engine.net.sync.PositionHistory
import com.oea.engine.physics.CharacterController
import com.oea.engine.physics.Layers
import com.oea.engine.physics.RayHit
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Data-driven weapon definitions: add a new gun by adding an entry. */
internal enum class Weapon(
    val label: String, val glyph: String, val damage: Float, val headshot: Float, val rpm: Float, val mag: Int, val reload: Float,
    val spreadHip: Float, val spreadAds: Float, val range: Float, val pellets: Int, val auto: Boolean, val ammo: Int, val zoom: Float, val recoil: Float,
) {
    PAN("Pan", "◍", 45f, 1.4f, 90f, 0, 0f, 0f, 0f, 2.2f, 1, false, -1, 1f, 0f),
    PISTOL("Pistol", "P", 26f, 1.8f, 380f, 12, 1.4f, 0.035f, 0.012f, 60f, 1, false, 0, 1.25f, 0.02f),
    SMG("SMG", "S", 17f, 1.6f, 820f, 30, 1.9f, 0.06f, 0.03f, 45f, 1, true, 0, 1.3f, 0.012f),
    RIFLE("Assault rifle", "A", 30f, 1.9f, 600f, 30, 2.3f, 0.045f, 0.012f, 110f, 1, true, 1, 1.6f, 0.018f),
    SHOTGUN("Shotgun", "G", 13f, 1.4f, 70f, 5, 2.8f, 0.12f, 0.085f, 24f, 9, false, 2, 1.2f, 0.06f),
    SNIPER("Sniper", "R", 105f, 2.2f, 42f, 5, 3.0f, 0.08f, 0.0015f, 260f, 1, false, 3, 4f, 0.09f);

    val interval get() = 60f / rpm

    companion object {
        val AMMO_NAMES = arrayOf("Light ammo", "Rifle ammo", "Shells", "Sniper ammo")
        val AMMO_PACK = intArrayOf(40, 40, 12, 10)
        val guns = entries.filter { it != PAN }
    }
}

internal enum class LootKind { WEAPON, AMMO, MEDKIT, ARMOR, HELMET }

internal class Loot(val id: Int, val kind: LootKind, val value: Int, var x: Float, var y: Float, var z: Float) {
    var taken = false
    val label: String get() = when (kind) {
        LootKind.WEAPON -> Weapon.entries[value].label
        LootKind.AMMO -> Weapon.AMMO_NAMES[value]
        LootKind.MEDKIT -> "Medkit"
        LootKind.ARMOR -> "Vest lv.$value"
        LootKind.HELMET -> "Helmet lv.$value"
    }
}

/** Input for one simulation tick (from touch controls, a client's packet or a bot). */
internal class PlayerInput {
    var seq = 0
    var moveX = 0f
    var moveY = 0f
    var yaw = 0f
    var pitch = 0f
    var fire = false
    var ads = false
    var jump = false
    var crouch = false
    var reload = false
    var slot = -1
    var heal = false
    var pickup = -1

    fun copyFrom(o: PlayerInput) {
        seq = o.seq; moveX = o.moveX; moveY = o.moveY; yaw = o.yaw; pitch = o.pitch; fire = o.fire; ads = o.ads
        jump = o.jump; crouch = o.crouch; reload = o.reload; slot = o.slot; heal = o.heal; pickup = o.pickup
    }
}

internal class ArenaPlayer(val id: Int, val name: String, val color: Int, val isBot: Boolean) {
    val ctrl = CharacterController(0.38f, 1.8f, 0.45f)
    val input = PlayerInput()
    var yaw = 0f
    var pitch = 0f
    var health = 100f
    var armor = 0
    var helmet = 0
    var alive = true
    var kills = 0
    var place = 0
    var killedBy = -1
    val slots = arrayOf(Weapon.PAN, Weapon.PAN)
    val mags = IntArray(2)
    var active = 0
    val ammo = IntArray(4)
    var medkits = 0
    var healProgress = 0f
    var cooldown = 0f
    var reloadLeft = 0f
    var switchLeft = 0f
    var crouching = false
    var aiming = false
    var skydiving = true
    var parachute = false
    var shots = 0
    var lastShotTime = 0f
    var spread = 0f
    /** View kick from recoil not yet applied by the controlling client (radians, upwards). */
    var recoil = 0f
    var lastDamageFrom = -1
    var lastDamageTime = -10f
    val history = PositionHistory(48)
    var lastAck = 0
    /** Bot brain. */
    var bot: BotBrain? = null

    val weapon: Weapon get() = slots[active]
    val eyeHeight get() = if (crouching) 1.05f else 1.6f
    val pos get() = ctrl.position
}

/** Callbacks for presentation (sounds, effects, kill feed, network events). */
internal interface ArenaEvents {
    fun onShot(p: ArenaPlayer, hitX: Float, hitY: Float, hitZ: Float, hitPlayer: Boolean) {}
    fun onHit(shooter: ArenaPlayer, victim: ArenaPlayer, damage: Float, headshot: Boolean) {}
    fun onKill(killer: ArenaPlayer?, victim: ArenaPlayer, weapon: Weapon, headshot: Boolean) {}
    fun onLootTaken(p: ArenaPlayer, loot: Loot) {}
    fun onLootSpawned(loot: Loot) {}
    fun onZone(phase: Int, shrinking: Boolean) {}
    fun onMatchOver(winner: ArenaPlayer?) {}
}

/** Safe zone that shrinks in phases, damaging players outside. */
internal class Zone(seed: Long, startRadius: Float, private val fast: Boolean) {
    private val rng = Rng(seed xor 0x2011E)
    var cx = 0f; var cz = 0f; var radius = startRadius
    var fromX = 0f; var fromZ = 0f; var fromR = startRadius
    var toX = 0f; var toZ = 0f; var toR = startRadius
    var phase = 0
    var timer = if (fast) 40f else 70f
    var shrinking = false
    val dps: Float get() = when (phase) { 0, 1 -> 1.5f; 2 -> 3f; 3 -> 6f; else -> 12f }

    init { pickNext() }

    private fun pickNext() {
        fromX = cx; fromZ = cz; fromR = radius
        toR = max(4f, radius * (if (phase < 3) 0.55f else 0.45f))
        val maxOff = radius - toR
        val a = rng.range(0f, MathX.TAU); val d = sqrt(rng.nextFloat()) * maxOff * 0.85f
        toX = cx + sin(a) * d; toZ = cz + cos(a) * d
    }

    /** Returns true when the phase changed. */
    fun update(dt: Float): Boolean {
        timer -= dt
        if (timer > 0f) {
            if (shrinking) {
                val total = if (fast) 22f else 35f
                val k = 1f - timer / total
                cx = MathX.lerp(fromX, toX, k); cz = MathX.lerp(fromZ, toZ, k); radius = MathX.lerp(fromR, toR, k)
            }
            return false
        }
        if (shrinking) {
            cx = toX; cz = toZ; radius = toR
            shrinking = false
            phase++
            pickNext()
            timer = if (fast) 30f else 55f
        } else {
            shrinking = true
            timer = if (fast) 22f else 35f
        }
        return true
    }

    fun outside(x: Float, z: Float): Boolean { val dx = x - cx; val dz = z - cz; return dx * dx + dz * dz > radius * radius }
}

/**
 * Authoritative battle-royale simulation (host or solo). Deterministic given
 * the seed and inputs; clients only predict their own movement.
 */
internal class ArenaSim(val seed: Long, val map: ArenaMap, private val fastZone: Boolean, private val botSkill: Float) {
    val players = LinkedHashMap<Int, ArenaPlayer>()
    val loot = ArrayList<Loot>()
    val zone = Zone(seed, ArenaMap.SIZE * 0.5f, fastZone)
    var time = 0f
        private set
    var events: ArenaEvents? = null
    var over = false
        private set
    var winner: ArenaPlayer? = null
        private set
    private var lootIds = 0
    private val rng = Rng(seed xor 0xA4E7A)
    private val hit = RayHit()
    private val tmp = Vec3()
    private val rewind = Vec3()

    val aliveCount: Int get() = players.values.count { it.alive }

    init { spawnLoot() }

    private fun spawnLoot() {
        for (s in map.lootSpots) {
            val r = rng.nextFloat()
            val kind = when {
                r < 0.38f -> LootKind.WEAPON
                r < 0.68f -> LootKind.AMMO
                r < 0.84f -> LootKind.MEDKIT
                r < 0.93f -> LootKind.ARMOR
                else -> LootKind.HELMET
            }
            val value = when (kind) {
                LootKind.WEAPON -> Weapon.guns[rng.weighted(floatArrayOf(1.2f, 1f, 1f, 0.7f, 0.35f))].ordinal
                LootKind.AMMO -> rng.nextInt(4)
                LootKind.ARMOR, LootKind.HELMET -> 1 + rng.weighted(floatArrayOf(1f, 0.5f, 0.18f))
                LootKind.MEDKIT -> 1
            }
            loot += Loot(lootIds++, kind, value, s[0] + rng.range(-0.6f, 0.6f), s[1], s[2] + rng.range(-0.6f, 0.6f))
            if (kind == LootKind.WEAPON) {
                val w = Weapon.entries[value]
                loot += Loot(lootIds++, LootKind.AMMO, w.ammo, s[0] + rng.range(-1f, 1f), s[1], s[2] + rng.range(-1f, 1f))
            }
        }
    }

    fun addPlayer(id: Int, name: String, color: Int, bot: Boolean): ArenaPlayer {
        val p = ArenaPlayer(id, name, color, bot)
        // Drop from the sky over a random point of the island.
        val a = rng.range(0f, MathX.TAU); val d = sqrt(rng.nextFloat()) * ArenaMap.SIZE * 0.32f
        val x = sin(a) * d; val z = cos(a) * d
        p.ctrl.teleport(x, map.heightAt(x, z) + 85f + rng.range(0f, 15f), z)
        p.yaw = rng.range(0f, MathX.TAU)
        p.input.yaw = p.yaw
        if (bot) p.bot = BotBrain(p, Rng(seed xor id.toLong() * 7919L), botSkill).also { it.attach(map.physics) }
        players[id] = p
        return p
    }

    fun step(dt: Float) {
        if (over) return
        time += dt
        for (p in players.values) {
            if (!p.alive) continue
            p.bot?.think(this, dt)
            applyInput(p, dt)
            p.history.record((time * 1000f).toLong(), p.pos.x, p.pos.y, p.pos.z)
        }
        if (zone.update(dt)) events?.onZone(zone.phase, zone.shrinking)
        for (p in players.values) if (p.alive && !p.skydiving && zone.outside(p.pos.x, p.pos.z)) damage(p, null, zone.dps * dt, Weapon.PAN, false, zoneDamage = true)
        if (players.size > 1 && aliveCount <= 1 && !over) {
            over = true
            winner = players.values.firstOrNull { it.alive }
            winner?.place = 1
            events?.onMatchOver(winner)
        }
    }

    /** Movement only (also used by clients to predict their own player). Returns false while skydiving. */
    fun move(p: ArenaPlayer, inp: PlayerInput, dt: Float): Boolean {
        p.yaw = inp.yaw
        p.pitch = MathX.clamp(inp.pitch, -1.2f, 1.1f)
        val fx = sin(p.yaw); val fz = cos(p.yaw)
        val rx = -fz; val rz = fx
        var mx = inp.moveX; var my = inp.moveY
        val len = sqrt(mx * mx + my * my)
        if (len > 1f) { mx /= len; my /= len }
        // Screen-right of the facing direction is (-fz, fx).
        val dirX = fx * my + rx * mx
        val dirZ = fz * my + rz * mx
        if (p.skydiving) {
            val ground = map.physics.groundHeight(p.pos.x, p.pos.z, p.pos.y)
            val above = p.pos.y - ground
            if (!p.parachute && above < 32f) p.parachute = true
            val speed = if (p.parachute) 7f else 12f
            p.ctrl.position.x += dirX * speed * dt
            p.ctrl.position.z += dirZ * speed * dt
            p.ctrl.position.y += (if (p.parachute) -5.5f else -22f) * dt
            if (p.ctrl.position.y <= ground + 0.05f) {
                p.ctrl.position.y = ground
                p.skydiving = false; p.parachute = false
                p.ctrl.velocity.setZero()
            }
            return false
        }
        p.crouching = inp.crouch
        p.aiming = inp.ads && p.weapon != Weapon.PAN
        val sprint = my > 0.92f && !p.aiming && !p.crouching && !inp.fire
        val speed = when {
            p.crouching -> 2.6f
            p.aiming -> 3.4f
            sprint -> 7.0f
            else -> 5.4f
        }
        p.ctrl.height = if (p.crouching) 1.2f else 1.8f
        p.ctrl.move(map.physics, dirX * speed, dirZ * speed, if (inp.jump && !p.crouching) 7.4f else 0f, dt)
        return true
    }

    private fun applyInput(p: ArenaPlayer, dt: Float) {
        val inp = p.input
        if (!move(p, inp, dt)) return
        val len = sqrt(inp.moveX * inp.moveX + inp.moveY * inp.moveY)
        // Weapons.
        if (p.cooldown > 0f) p.cooldown -= dt
        if (p.switchLeft > 0f) p.switchLeft -= dt
        if (inp.slot in 0..1 && inp.slot != p.active) { p.active = inp.slot; p.switchLeft = 0.45f; p.reloadLeft = 0f }
        val w = p.weapon
        if (p.reloadLeft > 0f) {
            p.reloadLeft -= dt
            if (p.reloadLeft <= 0f && w.ammo >= 0) {
                val need = w.mag - p.mags[p.active]
                val take = min(need, p.ammo[w.ammo])
                p.mags[p.active] += take; p.ammo[w.ammo] -= take
            }
        } else if (w.ammo >= 0 && (inp.reload || p.mags[p.active] == 0 && inp.fire) && p.mags[p.active] < w.mag && p.ammo[w.ammo] > 0) {
            p.reloadLeft = w.reload
        }
        val moving = len > 0.2f
        val target = (if (p.aiming) w.spreadAds else w.spreadHip) * (if (moving) 1.6f else 1f) * (if (!p.ctrl.grounded) 2.2f else 1f) * (if (p.crouching) 0.7f else 1f)
        p.spread = MathX.damp(p.spread, target, 8f, dt)
        if (inp.fire && p.cooldown <= 0f && p.reloadLeft <= 0f && p.switchLeft <= 0f) {
            if (w == Weapon.PAN || p.mags[p.active] > 0) fire(p)
            if (!w.auto) inp.fire = false
        }
        // Healing: 3 seconds, interrupted by shooting.
        if (inp.heal && p.medkits > 0 && p.health < 100f) {
            p.healProgress += dt / 3f
            if (p.healProgress >= 1f) { p.health = min(100f, p.health + 65f); p.medkits--; p.healProgress = 0f }
        } else p.healProgress = 0f
        // Pick-ups.
        autoPickup(p)
        if (inp.pickup >= 0) { pickup(p, inp.pickup); inp.pickup = -1 }
    }

    private fun fire(p: ArenaPlayer) {
        val w = p.weapon
        p.cooldown = w.interval
        p.shots++
        p.lastShotTime = time
        if (w != Weapon.PAN) p.mags[p.active]--
        val ex = p.pos.x; val ey = p.pos.y + p.eyeHeight; val ez = p.pos.z
        val cp = cos(p.pitch)
        val bx = sin(p.yaw) * cp; val by = sin(p.pitch); val bz = cos(p.yaw) * cp
        val shotTime = (time * 1000f).toLong()
        for (k in 0 until w.pellets) {
            var dx = bx + rng.gaussian() * p.spread * 0.5f
            var dy = by + rng.gaussian() * p.spread * 0.5f
            var dz = bz + rng.gaussian() * p.spread * 0.5f
            val l = sqrt(dx * dx + dy * dy + dz * dz); dx /= l; dy /= l; dz /= l
            var dist = w.range
            var hitWorld = false
            if (map.physics.raycast(ex, ey, ez, dx, dy, dz, w.range, hit, Layers.WORLD or Layers.PROPS)) { dist = hit.distance; hitWorld = true }
            var victim: ArenaPlayer? = null
            var head = false
            for (o in players.values) {
                if (o === p || !o.alive || o.skydiving) continue
                // Lag compensation: test where the shooter saw the target.
                val rewindMs = p.bot?.let { 0L } ?: compensation(p)
                if (!o.history.sample(shotTime - rewindMs, rewind)) rewind.set(o.pos)
                val oy = if (o.crouching) 1.05f else 1.6f
                val th = Intersect.raySphere(ex, ey, ez, dx, dy, dz, rewind.x, rewind.y + oy + 0.02f, rewind.z, 0.24f)
                val tb = Intersect.rayCapsule(ex, ey, ez, dx, dy, dz, rewind.x, rewind.y + 0.3f, rewind.z, rewind.x, rewind.y + oy - 0.3f, rewind.z, 0.36f)
                val t = when { th >= 0f && (tb < 0f || th <= tb) -> th; else -> tb }
                if (t in 0f..dist) { dist = t; victim = o; head = t == th }
            }
            val hx = ex + dx * dist; val hy = ey + dy * dist; val hz = ez + dz * dist
            if (victim != null) {
                val falloff = if (dist > w.range * 0.5f) MathX.lerp(1f, 0.6f, (dist - w.range * 0.5f) / (w.range * 0.5f)) else 1f
                val dmg = w.damage * falloff * (if (head) w.headshot else 1f)
                damage(victim, p, dmg, w, head)
                events?.onShot(p, hx, hy, hz, true)
            } else if (k == 0 || w.pellets > 1 && k % 3 == 0) {
                events?.onShot(p, hx, hy, hz, false)
            }
            @Suppress("UNUSED_VARIABLE") val unused = hitWorld
        }
        // Recoil kicks the view up a little (the local controller applies it to its camera).
        if (p.isBot) p.input.pitch += w.recoil * 0.3f else p.recoil += w.recoil
    }

    /** How far back to rewind for a human shooter (their latency + interpolation delay). */
    var compensation: (ArenaPlayer) -> Long = { 0L }

    fun damage(v: ArenaPlayer, from: ArenaPlayer?, amount: Float, w: Weapon, head: Boolean, zoneDamage: Boolean = false) {
        if (!v.alive) return
        var dmg = amount
        if (!zoneDamage) {
            dmg *= if (head) 1f - v.helmet * 0.18f else 1f - v.armor * 0.17f
            v.lastDamageFrom = from?.id ?: -1
            v.lastDamageTime = time
            if (from != null) events?.onHit(from, v, dmg, head)
        }
        v.health -= dmg
        v.healProgress = 0f
        if (v.health <= 0f) {
            v.health = 0f
            v.alive = false
            v.place = aliveCount + 1
            v.killedBy = from?.id ?: -1
            from?.let { it.kills++ }
            dropLoot(v)
            events?.onKill(from, v, w, head)
        }
    }

    private fun dropLoot(v: ArenaPlayer) {
        fun drop(kind: LootKind, value: Int, ox: Float, oz: Float) {
            val l = Loot(lootIds++, kind, value, v.pos.x + ox, v.pos.y + 0.3f, v.pos.z + oz)
            loot += l
            events?.onLootSpawned(l)
        }
        v.slots.forEachIndexed { i, w -> if (w != Weapon.PAN) drop(LootKind.WEAPON, w.ordinal, if (i == 0) -0.7f else 0.7f, 0f) }
        v.ammo.forEachIndexed { i, a -> if (a > 0) drop(LootKind.AMMO, i, -0.5f + i * 0.35f, 0.8f) }
        if (v.medkits > 0) drop(LootKind.MEDKIT, 1, 0f, -0.8f)
        if (v.armor > 0) drop(LootKind.ARMOR, v.armor, 0.8f, -0.6f)
    }

    private fun autoPickup(p: ArenaPlayer) {
        for (l in loot) {
            if (l.taken) continue
            val dx = l.x - p.pos.x; val dz = l.z - p.pos.z
            if (dx * dx + dz * dz > 2.4f || abs(l.y - p.pos.y - 0.3f) > 1.6f) continue
            val wanted = when (l.kind) {
                LootKind.AMMO -> true
                LootKind.MEDKIT -> p.medkits < 5
                LootKind.ARMOR -> l.value > p.armor
                LootKind.HELMET -> l.value > p.helmet
                LootKind.WEAPON -> p.slots.any { it == Weapon.PAN } && p.slots.none { it.ordinal == l.value }
            }
            if (wanted) take(p, l)
        }
    }

    /** Explicit pick-up (weapons replace the active slot). */
    fun pickup(p: ArenaPlayer, lootId: Int) {
        val l = loot.firstOrNull { it.id == lootId && !it.taken } ?: return
        val dx = l.x - p.pos.x; val dz = l.z - p.pos.z
        if (dx * dx + dz * dz > 6f) return
        if (l.kind == LootKind.WEAPON) {
            val old = p.slots[p.active]
            if (old != Weapon.PAN) {
                val drop = Loot(lootIds++, LootKind.WEAPON, old.ordinal, p.pos.x, p.pos.y + 0.3f, p.pos.z)
                loot += drop
                events?.onLootSpawned(drop)
            }
            p.slots[p.active] = Weapon.entries[l.value]
            p.mags[p.active] = min(Weapon.entries[l.value].mag, 6)
            p.reloadLeft = 0f
            l.taken = true
            events?.onLootTaken(p, l)
        } else take(p, l)
    }

    private fun take(p: ArenaPlayer, l: Loot) {
        when (l.kind) {
            LootKind.AMMO -> p.ammo[l.value] += Weapon.AMMO_PACK[l.value]
            LootKind.MEDKIT -> p.medkits++
            LootKind.ARMOR -> p.armor = l.value
            LootKind.HELMET -> p.helmet = l.value
            LootKind.WEAPON -> {
                val slot = if (p.slots[0] == Weapon.PAN) 0 else 1
                p.slots[slot] = Weapon.entries[l.value]
                p.mags[slot] = min(Weapon.entries[l.value].mag, 6)
                if (p.slots[p.active] == Weapon.PAN) p.active = slot
            }
        }
        l.taken = true
        events?.onLootTaken(p, l)
    }

    fun nearestLoot(p: ArenaPlayer, kind: LootKind? = null, maxDist: Float = 2.6f): Loot? {
        var best: Loot? = null; var bd = maxDist * maxDist
        for (l in loot) {
            if (l.taken || (kind != null && l.kind != kind)) continue
            val dx = l.x - p.pos.x; val dz = l.z - p.pos.z
            val d = dx * dx + dz * dz
            if (d < bd && abs(l.y - p.pos.y) < 2f) { bd = d; best = l }
        }
        return best
    }

    fun lineOfSight(a: ArenaPlayer, b: ArenaPlayer): Boolean =
        map.physics.lineOfSight(a.pos.x, a.pos.y + a.eyeHeight, a.pos.z, b.pos.x, b.pos.y + 1.2f, b.pos.z, Layers.WORLD or Layers.PROPS, hit)

    @Suppress("unused") private fun unused() = atan2(0f, 1f) + tmp.x
}

/**
 * Bot behaviour: loot when unarmed, move into the zone, fight visible enemies
 * with human-like reaction time, aim error, strafing and crouching.
 */
internal class BotBrain(private val p: ArenaPlayer, private val rng: Rng, private val skill: Float) {
    private var target: ArenaPlayer? = null
    private var thinkTimer = 0f
    private var reaction = 0f
    private var goalX = 0f
    private var goalZ = 0f
    private var hasGoal = false
    private var strafe = 1f
    private var strafeTimer = 0f
    private var aimError = 0.5f
    private var stuckTimer = 0f
    private var lastX = 0f
    private var lastZ = 0f
    private var burst = 0f
    private val hit = RayHit()

    fun think(sim: ArenaSim, dt: Float) {
        val inp = p.input
        inp.jump = false; inp.reload = false; inp.slot = -1; inp.pickup = -1
        if (p.skydiving) {
            if (!hasGoal) pickGoal(sim)
            steerTo(goalX, goalZ, inp, 1f)
            return
        }
        thinkTimer -= dt
        if (thinkTimer <= 0f) {
            thinkTimer = 0.25f
            perceive(sim)
            // Stuck detection.
            val moved = MathX.length(p.pos.x - lastX, p.pos.z - lastZ)
            lastX = p.pos.x; lastZ = p.pos.z
            stuckTimer = if (moved < 0.25f && (inp.moveX != 0f || inp.moveY != 0f)) stuckTimer + 0.25f else 0f
            if (stuckTimer > 1.5f) { hasGoal = false; stuckTimer = 0f; inp.jump = true }
        }
        val t = target
        val hasGun = p.slots.any { it != Weapon.PAN }
        // Prefer a loaded weapon.
        val best = (0..1).maxByOrNull { i -> if (p.slots[i] == Weapon.PAN) -1 else p.mags[i] + p.ammo[p.slots[i].ammo.coerceAtLeast(0)] }!!
        if (best != p.active && p.slots[best] != Weapon.PAN) inp.slot = best
        if (p.health < 55f && p.medkits > 0 && t == null) { inp.heal = true; inp.moveX = 0f; inp.moveY = 0f; inp.fire = false; return }
        inp.heal = false
        if (t != null && t.alive && hasGun) {
            fight(sim, t, inp, dt)
            return
        }
        inp.fire = false; inp.ads = false; inp.crouch = false
        // Outside (or soon outside) the zone: head in.
        if (sim.zone.outside(p.pos.x, p.pos.z) || (sim.zone.shrinking && MathX.length(p.pos.x - sim.zone.toX, p.pos.z - sim.zone.toZ) > sim.zone.toR * 0.8f)) {
            goalX = sim.zone.toX + rng.range(-5f, 5f); goalZ = sim.zone.toZ + rng.range(-5f, 5f); hasGoal = true
        } else if (!hasGun || p.ammo.sum() < 20 || p.armor == 0) {
            val l = nearestUseful(sim)
            if (l != null) {
                goalX = l.x; goalZ = l.z; hasGoal = true
                if (MathX.length(l.x - p.pos.x, l.z - p.pos.z) < 1.6f && l.kind == LootKind.WEAPON) inp.pickup = l.id
            }
        }
        if (!hasGoal || MathX.length(goalX - p.pos.x, goalZ - p.pos.z) < 2f) pickGoal(sim)
        steerTo(goalX, goalZ, inp, 0.9f)
        if (p.mags[p.active] < p.weapon.mag / 2 && p.weapon != Weapon.PAN) inp.reload = true
    }

    private fun perceive(sim: ArenaSim) {
        var best: ArenaPlayer? = null
        var bestD = 60f + skill * 30f
        for (o in sim.players.values) {
            if (o === p || !o.alive || o.skydiving) continue
            val dx = o.pos.x - p.pos.x; val dz = o.pos.z - p.pos.z
            val d = sqrt(dx * dx + dz * dz)
            if (d > bestD) continue
            val ang = abs(MathX.angleDelta(p.yaw, atan2(dx, dz)))
            val heard = d < 14f || (sim.time - o.lastShotTime < 1f && d < 45f) || (p.lastDamageFrom == o.id && sim.time - p.lastDamageTime < 2f)
            if (ang > 1.2f && !heard) continue
            if (!sim.lineOfSight(p, o)) continue
            best = o; bestD = d
        }
        if (best != null && best !== target) { reaction = MathX.lerp(0.75f, 0.18f, skill) + rng.range(0f, 0.2f); aimError = MathX.lerp(0.45f, 0.12f, skill) }
        target = best
    }

    private fun fight(sim: ArenaSim, t: ArenaPlayer, inp: PlayerInput, dt: Float) {
        val dx = t.pos.x - p.pos.x; val dz = t.pos.z - p.pos.z
        val dist = sqrt(dx * dx + dz * dz)
        val wantYaw = atan2(dx, dz)
        val wantPitch = atan2(t.pos.y + (if (t.crouching) 0.8f else 1.25f) - (p.pos.y + p.eyeHeight), dist)
        val turn = MathX.lerp(2.5f, 7f, skill) * dt
        inp.yaw = p.yaw + MathX.clamp(MathX.angleDelta(p.yaw, wantYaw + aimError * 0.35f), -turn, turn)
        inp.pitch = MathX.lerp(inp.pitch, wantPitch + aimError * 0.1f, 0.25f)
        aimError = MathX.damp(aimError, 0.02f + (1f - skill) * 0.05f, 1.5f + skill * 2f, dt)
        reaction -= dt
        val w = p.weapon
        inp.ads = dist > 18f && w != Weapon.SHOTGUN
        val aligned = abs(MathX.angleDelta(p.yaw, wantYaw)) < 0.12f
        burst -= dt
        if (reaction <= 0f && aligned && dist < w.range * 0.9f) {
            if (w.auto) {
                if (burst <= -0.35f) burst = rng.range(0.25f, 0.7f)
                inp.fire = burst > 0f
            } else inp.fire = rng.chance(0.5f)
        } else inp.fire = false
        // Movement: strafe, keep a good distance for the weapon.
        strafeTimer -= dt
        if (strafeTimer <= 0f) { strafe = if (rng.chance(0.5f)) 1f else -1f; strafeTimer = rng.range(0.6f, 1.8f); inp.crouch = rng.chance(0.25f * skill) }
        val ideal = when (w) { Weapon.SHOTGUN -> 6f; Weapon.SMG -> 12f; Weapon.SNIPER -> 45f; else -> 20f }
        inp.moveX = strafe * 0.8f
        inp.moveY = if (dist > ideal * 1.3f) 0.8f else if (dist < ideal * 0.6f) -0.6f else 0f
        if (p.mags[p.active] == 0) inp.reload = true
        if (rng.chance(0.004f + 0.01f * skill)) inp.jump = true
    }

    private fun nearestUseful(sim: ArenaSim): Loot? {
        var best: Loot? = null; var bd = 35f * 35f
        for (l in sim.loot) {
            if (l.taken) continue
            val useful = when (l.kind) {
                LootKind.WEAPON -> p.slots.any { it == Weapon.PAN }
                LootKind.AMMO -> p.slots.any { it.ammo == l.value }
                LootKind.ARMOR -> l.value > p.armor
                LootKind.HELMET -> l.value > p.helmet
                LootKind.MEDKIT -> p.medkits < 3
            }
            if (!useful) continue
            val dx = l.x - p.pos.x; val dz = l.z - p.pos.z
            val d = dx * dx + dz * dz
            if (d < bd) { bd = d; best = l }
        }
        return best
    }

    private fun pickGoal(sim: ArenaSim) {
        val z = sim.zone
        val r = (if (z.shrinking) z.toR else z.radius) * 0.75f
        val a = rng.range(0f, MathX.TAU); val d = sqrt(rng.nextFloat()) * r
        goalX = (if (z.shrinking) z.toX else z.cx) + sin(a) * d
        goalZ = (if (z.shrinking) z.toZ else z.cz) + cos(a) * d
        // Prefer towns early on.
        if (sim.time < 60f && rng.chance(0.7f) && sim.map.towns.isNotEmpty()) {
            val t = sim.map.towns[rng.nextInt(sim.map.towns.size)]
            goalX = t[0] + rng.range(-8f, 8f); goalZ = t[1] + rng.range(-8f, 8f)
        }
        hasGoal = true
    }

    private fun steerTo(x: Float, z: Float, inp: PlayerInput, speed: Float) {
        val dx = x - p.pos.x; val dz = z - p.pos.z
        var yaw = atan2(dx, dz)
        // Feelers: if a wall is right ahead, turn towards the clearer side.
        if (!p.skydiving) {
            val ey = p.pos.y + 0.9f
            val ahead = p.pos
            fun blocked(a: Float) = sim(ahead.x, ey, ahead.z, sin(a), cos(a))
            if (blocked(yaw)) {
                yaw = when {
                    !blocked(yaw + 0.7f) -> yaw + 0.7f
                    !blocked(yaw - 0.7f) -> yaw - 0.7f
                    !blocked(yaw + 1.5f) -> yaw + 1.5f
                    else -> yaw - 1.5f
                }
            }
        }
        inp.yaw = MathX.lerpAngle(p.yaw, yaw, 0.2f)
        inp.pitch = MathX.lerp(inp.pitch, 0f, 0.1f)
        inp.moveX = 0f
        inp.moveY = if (MathX.length(dx, dz) > 1.2f) speed else 0f
    }

    private var physicsRef: com.oea.engine.physics.PhysicsWorld? = null
    fun attach(world: com.oea.engine.physics.PhysicsWorld) { physicsRef = world }
    private fun sim(x: Float, y: Float, z: Float, dx: Float, dz: Float): Boolean =
        physicsRef?.raycast(x, y, z, dx, 0f, dz, 2.2f, hit, Layers.WORLD or Layers.PROPS) == true
}
