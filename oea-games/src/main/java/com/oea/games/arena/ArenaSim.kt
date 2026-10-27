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
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Data-driven weapon definitions: add a new gun by adding an entry. */
internal enum class Weapon(
    val label: String, val glyph: String, val damage: Float, val headshot: Float, val rpm: Float, val mag: Int, val reload: Float,
    val spreadHip: Float, val spreadAds: Float, val range: Float, val pellets: Int, val auto: Boolean, val ammo: Int, val zoom: Float, val recoil: Float,
    /** Movement multiplier while this weapon is held/aimed (heavy guns slow you down). */
    val moveSpeed: Float = 1f,
    /** Clash Squad shop price. */
    val price: Int = 0,
) {
    PAN("Pan", "◍", 45f, 1.4f, 90f, 0, 0f, 0f, 0f, 2.2f, 1, false, -1, 1f, 0f, price = 100),
    PISTOL("Pistol", "P", 26f, 1.8f, 380f, 12, 1.4f, 0.035f, 0.012f, 60f, 1, false, 0, 1.25f, 0.02f, price = 500),
    SMG("SMG", "S", 17f, 1.6f, 820f, 30, 1.9f, 0.06f, 0.03f, 45f, 1, true, 0, 1.3f, 0.012f, price = 800),
    RIFLE("Assault rifle", "A", 30f, 1.9f, 600f, 30, 2.3f, 0.045f, 0.012f, 110f, 1, true, 1, 1.6f, 0.018f, price = 1200),
    SHOTGUN("Shotgun", "G", 13f, 1.4f, 70f, 5, 2.8f, 0.12f, 0.085f, 24f, 9, false, 2, 1.2f, 0.06f, 0.96f, price = 900),
    SNIPER("Sniper", "R", 105f, 2.2f, 42f, 5, 3.0f, 0.08f, 0.0015f, 260f, 1, false, 3, 4f, 0.09f, 0.92f, price = 1600),
    REVOLVER("Revolver", "V", 58f, 2.0f, 150f, 6, 2.2f, 0.05f, 0.008f, 90f, 1, false, 3, 1.9f, 0.055f, 0.97f, price = 1000),
    MARKSMAN("Marksman", "M", 44f, 2.0f, 260f, 12, 2.4f, 0.05f, 0.002f, 170f, 1, false, 1, 2.8f, 0.045f, 0.95f, price = 1300),
    LMG("LMG", "L", 24f, 1.55f, 700f, 75, 4.2f, 0.065f, 0.03f, 130f, 1, true, 1, 1.45f, 0.022f, 0.90f, price = 1500),
    ROCKET("Rocket launcher", "◎", 140f, 1f, 24f, 1, 3.4f, 0.06f, 0.03f, 250f, 1, false, 4, 1.05f, 0.04f, 0.74f, price = 2200),
    MISSILE("Guided missile", "☆", 120f, 1f, 18f, 1, 4.2f, 0.05f, 0.03f, 320f, 1, false, 4, 1.15f, 0.05f, 0.88f, price = 2600);

    val interval get() = 60f / rpm

    companion object {
        val AMMO_NAMES = arrayOf("Light ammo", "Rifle ammo", "Shells", "Heavy ammo", "Special ammo")
        val AMMO_PACK = intArrayOf(40, 40, 12, 10, 6)
        val guns = entries.filter { it != PAN }

        /** Which guns the loot tables draw from for a loadout option. */
        fun lootPool(loadout: String): List<Weapon> = when (loadout) {
            "snipers" -> listOf(SNIPER, MARKSMAN, REVOLVER)
            "shotguns" -> listOf(SHOTGUN, SMG, PISTOL)
            "close" -> listOf(PISTOL, SMG, REVOLVER)
            "rifles" -> listOf(RIFLE, SMG, LMG, MARKSMAN)
            else -> guns
        }
    }
}

/** Gun Game ladder: one kill advances a stage; a kill with the final stage wins. */
internal val GUN_LADDER = listOf(
    Weapon.PISTOL, Weapon.SMG, Weapon.RIFLE, Weapon.SHOTGUN,
    Weapon.REVOLVER, Weapon.MARKSMAN, Weapon.SNIPER, Weapon.LMG, Weapon.PAN,
)

/** Kills needed to win Team Deathmatch. */
private const val TEAM_SCORE_WIN = 40

internal enum class LootKind { WEAPON, AMMO, MEDKIT, ARMOR, HELMET, GRENADE }

/** Grenade types: 0 frag (damage), 1 smoke (cloud), 2 stun (flash + slow). */
private const val GRENADE_FRAG = 0
private const val GRENADE_SMOKE = 1
private const val GRENADE_STUN = 2
/** Rocket (3) and guided missile (3 as well) explosions — bigger blasts. */
private const val GRENADE_ROCKET = 3
/** Clash Squad shop prices. */
internal const val SHOP_MEDKIT = 300
internal const val SHOP_FRAG = 400
internal const val SHOP_SMOKE = 500
internal const val SHOP_STUN = 450
internal const val SHOP_VEST = 600
internal const val SHOP_HELMET = 500
/** Weapon upgrade costs: base per level, up to UP_MAX levels per weapon. */
internal const val UP_BASE = 250
internal const val UP_MAX = 3

internal class Loot(val id: Int, val kind: LootKind, val value: Int, var x: Float, var y: Float, var z: Float) {
    var taken = false
    val label: String get() = when (kind) {
        LootKind.WEAPON -> Weapon.entries[value].label
        LootKind.AMMO -> Weapon.AMMO_NAMES[value]
        LootKind.MEDKIT -> "Medkit"
        LootKind.ARMOR -> "Vest lv.$value"
        LootKind.HELMET -> "Helmet lv.$value"
        LootKind.GRENADE -> when (value) {
            GRENADE_FRAG -> "Frag grenade"
            GRENADE_SMOKE -> "Smoke grenade"
            else -> "Stun grenade"
        }
    }
}

/**
 * A thrown grenade: arcs on gravity, bounces once off the terrain, then
 * explodes on its fuse (type 0 = frag with a damage radius, 1 = smoke cloud).
 */
internal class Grenade(
    var x: Float, var y: Float, var z: Float,
    var vx: Float, var vy: Float, var vz: Float,
    val type: Int,
    fuse: Float = 2.4f,
    /** Rocket (unguided) or guided missile: fly straight/home instead of arcing. */
    val rocket: Boolean = false,
    val missile: Boolean = false,
    /** Missile lock target (player id) — re-locks to the nearest enemy while alive. */
    var homing: Int = -1,
    /** Thrower's team — the missile won't lock teammates. */
    val team: Int = 0,
    /** Seconds of flight left before the warhead self-destructs. */
    var life: Float = 12f,
) {
    var fuse = fuse
    var bounces = 0
    var dead = false
    /** Blinks faster as the fuse burns down — the last half-second screams. */
    val fuseLeft: Float get() = fuse
}

/** A driveable combat vehicle: 0 jeep (fast transport), 1 tank (cannon), 2 turret (missiles). */
internal class Vehicle(
    val id: Int, var x: Float, var y: Float, var z: Float, var yaw: Float,
    val kind: Int, val radius: Float, val maxHealth: Float, val speed: Float,
    var health: Float = maxHealth,
    /** Player id at the wheel (-1 = empty). */
    var driver: Int = -1,
    /** Current speed (for contact damage). */
    var speedNow: Float = 0f,
    var dead: Boolean = false,
    /** Warfare vehicles respawn at their spawn point after a short wait. */
    var respawnAt: Float = 0f,
) {
    val isTank get() = kind == 1
    val isTurret get() = kind == 2
    val isHelo get() = kind == 3
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
    /** One-shot throw commands: grenade (bit 6) and smoke (bit 7). */
    var grenade = false
    var smoke = false
    /** Stun throwable (second flags byte, bit 0). */
    var stun = false
    /** Vehicle mount/dismount (second flags byte, bit 8). */
    var mount = false
    /** One-shot buy: upgrade the weapon in this slot (-1 = none). */
    var buyUpSlot = -1
    /** One-shot: reload every weapon in the pack (second flags byte, bit 1). */
    var reloadAll = false
    /** How long the frag fuse was cooked before the throw (0 = none). */
    var cook = 0f
    /** Prone state (long-press crouch). */
    var prone = false

    /** One-shot: deploy the next round (the host starts it). */
    var deployRound = false
    /** One-shot: the client left the round-over screen — the host opens the shop. */
    var advancePrep = false
    /** Clash Squad shop purchases — applied by the host during the prep phase. */
    var buySlot = -1
    var buyMedkits = 0
    var buyFrags = 0
    var buySmokes = 0
    var buyStuns = 0
    var buyVest = 0
    var buyHelmet = 0

    fun copyFrom(o: PlayerInput) {
        seq = o.seq; moveX = o.moveX; moveY = o.moveY; yaw = o.yaw; pitch = o.pitch; fire = o.fire; ads = o.ads
        jump = o.jump; crouch = o.crouch; reload = o.reload; slot = o.slot; heal = o.heal; pickup = o.pickup
        grenade = o.grenade; smoke = o.smoke; stun = o.stun; cook = o.cook; prone = o.prone; reloadAll = o.reloadAll
        deployRound = o.deployRound; advancePrep = o.advancePrep
        buySlot = o.buySlot; buyMedkits = o.buyMedkits; buyFrags = o.buyFrags; buySmokes = o.buySmokes
        buyStuns = o.buyStuns; buyVest = o.buyVest; buyHelmet = o.buyHelmet
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
    val ammo = IntArray(5)
    var medkits = 0
    /** Frag grenades (value 0) and smoke grenades (value 1) in the pack. */
    var grenades = 0
    var smokes = 0
    /** Stun grenades (value 2) in the pack. */
    var stuns = 0
    /** Clash Squad money: earned by kills and round results, spent in the shop. */
    var cash = 0
    /** Seconds left of stun from a stun grenade — movement crawls, spread opens. */
    var stunTimer = 0f
    /** Lying flat: slower than crouch, steadier aim, smaller than crouch. */
    var prone = false
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
    /** Team 0/1 in Team Deathmatch (0 in free-for-all rules). */
    var team = 0
    /** Seconds of spawn protection left after a respawn. */
    var spawnGuard = 0f
    /** Simulation time at which a dead player respawns (respawn modes). */
    var respawnAt = 0f
    /** Gun Game ladder index into [GUN_LADDER]. */
    var gunLevel = 0
    /** Vehicle this player is riding (vehicle id, or -1). */
    var vehicle = -1
    /** Per-weapon upgrade levels bought with cash (0-3). */
    var upLevels = IntArray(Weapon.entries.size)
    var deaths = 0
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
    /** A grenade went off: type 0 = frag burst with damage, 1 = smoke cloud, 2 = stun flash. */
    fun onGrenade(x: Float, y: Float, z: Float, type: Int) {}
    /** A round started (round modes only). */
    fun onRoundStart(round: Int) {}
    /** A round ended: winTeam took it (round modes only). */
    fun onRoundOver(winTeam: Int, round: Int) {}
    /** Warfare: a capture point flipped to `team`. */
    fun onPointCaptured(x: Float, z: Float, team: Int) {}
}

/** Safe zone that shrinks in phases, damaging players outside. */
internal class Zone(seed: Long, startRadius: Float, private val fast: Boolean) {
    /** Survival modes turn the zone off: the whole island stays in play. */
    var enabled = true
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
 *
 * Rules change the *simulation*, not just the lighting: `tdm` adds teams,
 * respawns and a score race, `gun` runs the Gun Game ladder with instant
 * respawns and no loot, the survival rules spawn endless waves.
 */
internal class ArenaSim(
    val seed: Long,
    val map: ArenaMap,
    private val fastZone: Boolean,
    private val botSkill: Float,
    val rules: String = "br",
    val loadout: String = "all",
) {
    val players = LinkedHashMap<Int, ArenaPlayer>()
    val loot = ArrayList<Loot>()
    val grenades = ArrayList<Grenade>()
    /** Driveable vehicles (Warfare). */
    val vehicles = ArrayList<Vehicle>()
    /** Smoke clouds: [x, y, z, ttl-seconds] — the cloud hides anyone inside it. */
    val smokes = ArrayList<FloatArray>(4)
    val zone = Zone(seed, ArenaMap.SIZE * 0.5f, fastZone)
    var time = 0f
        private set
    var events: ArenaEvents? = null
    var over = false
        private set
    var winner: ArenaPlayer? = null
        private set
    /** Team scores for [rules] == "tdm" (index 0/1). */
    val teamScore = IntArray(2)
    /** Warfare: capture events per team (first to [WARFARE_WIN] takes the match). */
    val captures = IntArray(2)
    private const val WARFARE_WIN = 5
    /** King of the Hill: score accumulated while holding the single hill. */
    val kothScore = FloatArray(2)
    private const val KOTH_WIN = 200
    private const val KOTH_RATE = 1.2f
    val teams = rules == "tdm" || rules == "cs" || rules == "warfare" || rules == "wolf" || rules == "warfare_koth"
    val gunGame = rules == "gun"
    /** Respawn after death — except the round-based duels, which respawn at round start. */
    val respawnMode = rules == "tdm" || rules == "gun" || rules == "warfare" || rules == "br_res" || rules == "warfare_koth"
    val survival = rules == "last_day" || rules == "lights_out" || rules == "haunted"
    /** Round-based modes (Clash Squad, Lone Wolf): best-of-N, shop/pick between rounds. */
    val roundMode = rules == "cs" || rules == "wolf"
    /** Delta Force-style objective warfare: respawn with kit, capture the points. */
    val warfare = rules == "warfare" || rules == "warfare_koth"
    /** Warfare variant: one hill, score while you hold it. */
    val koth = rules == "warfare_koth"
    /** CoD-style royale: the zone closes, but you come back. */
    val resurgence = rules == "br_res"
    var round = 1
    /** 0 combat · 1 round over · 2 shop/pick prep (round modes only). */
    var roundPhase = 0
    val roundsWon = IntArray(2)
    /** First to this many round wins takes the match (4 = best of 7, 3 = best of 5). */
    val roundsToWin = if (rules == "wolf") 3 else 4
    /** Warfare capture points: three objectives spread across the map. */
    val points = ArrayList<CapturePoint>(3)

    /** A warfare objective: who holds it, and how full the capture bar is. */
    class CapturePoint(val x: Float, val z: Float, val r: Float = 4.5f) {
        var owner = -1
        var progress = 0f
    }
    private var lootIds = 0
    private val rng = Rng(seed xor 0xA4E7A)
    private val hit = RayHit()
    private val tmp = Vec3()
    private val rewind = Vec3()

    val aliveCount: Int get() = players.values.count { it.alive }

    private var nextBotId = 120

    /**
     * Bot ids stay inside the u8 snapshot range (2–250). In survival and
     * respawn modes the longest-dead bots are recycled so endless waves never
     * overflow the roster.
     */
    fun allocBotId(): Int {
        if (respawnMode || survival) {
            while (players.size > 72) {
                val dead = players.values.firstOrNull { !it.alive && it.isBot } ?: break
                players.remove(dead.id)
            }
        }
        while (players.containsKey(nextBotId)) nextBotId = if (nextBotId >= 250) 120 else nextBotId + 1
        return nextBotId++
    }

    init {
        if (respawnMode || survival || roundMode || warfare) zone.enabled = false
        // TDM and Gun Game are clean competitive arenas: they start with a
        // deliberate kit and never turn the score race into a loot sweep.
        if (!gunGame && !teams) spawnLoot()
        if (warfare) {
            // Objective towns: prefer real towns; fall back to a triangle around the center.
            val towns = map.towns
            val pool: List<FloatArray> = if (towns.size >= 3) towns
            else {
                val c = ArenaMap.SIZE * 0.5f; val o = ArenaMap.SIZE * 0.3f
                listOf(floatArrayOf(c - o, c - o), floatArrayOf(c + o, c - o), floatArrayOf(c, c + o))
            }
            if (koth) {
                // King of the Hill: one hill on a town (or the map centre),
                // with a wide radius so a squad can actually hold it.
                val t = pool[0]
                points += CapturePoint(t[0], t[1], r = 6f)
            } else {
                points += CapturePoint(pool[0][0], pool[0][1])
                points += CapturePoint(pool[1][0], pool[1][1])
                points += CapturePoint(pool[2][0], pool[2][1])
            }
            // Warfare motor pool (Delta Force style): jeeps for fast transit, a
            // tank for breakthroughs, a mobile turret for lock-on missile support.
            for (k in 0..1) {
                val t = pool[k]
                vehicles += Vehicle(vehicles.size, t[0], map.heightAt(t[0], t[1]), t[1], 0f, kind = 0, radius = 2.2f, maxHealth = 180f, speed = 17f)
            }
            val tt = pool[2]
            vehicles += Vehicle(vehicles.size, tt[0], map.heightAt(tt[0], tt[1]), tt[1], 0f, kind = 1, radius = 2.6f, maxHealth = 420f, speed = 9f)
            val tc = ArenaMap.SIZE * 0.5f
            vehicles += Vehicle(vehicles.size, tc, map.heightAt(tc, tc), tc, 0f, kind = 2, radius = 2.0f, maxHealth = 240f, speed = 3f)
            // Air wing: one helo at the centre for fast crossings and high-altitude ram runs.
            vehicles += Vehicle(vehicles.size, tc + 8f, map.heightAt(tc + 8f, tc), tc + 8f, 0f, kind = 3, radius = 2.8f, maxHealth = 200f, speed = 14f)
        }
    }

    private fun spawnLoot() {
        val pool = Weapon.lootPool(loadout)
        val weights = FloatArray(pool.size) { i -> 0.5f + 1.2f / (1f + i) }
        for (s in map.lootSpots) {
            val r = rng.nextFloat()
            val kind = when {
                r < 0.38f -> LootKind.WEAPON
                r < 0.68f -> LootKind.AMMO
                r < 0.84f -> LootKind.MEDKIT
                r < 0.93f -> LootKind.ARMOR
                r < 0.97f -> LootKind.HELMET
                else -> LootKind.GRENADE
            }
            // Throwable mix: frag most common, then smoke, then stun.
            val grenValue = if (rng.chance(0.6f)) GRENADE_FRAG else if (rng.chance(0.6f)) GRENADE_SMOKE else GRENADE_STUN
            val value = when (kind) {
                LootKind.WEAPON -> pool[rng.weighted(weights)].ordinal
                LootKind.AMMO -> rng.nextInt(4)
            LootKind.ARMOR, LootKind.HELMET -> 1 + rng.weighted(floatArrayOf(1f, 0.5f, 0.18f))
                LootKind.MEDKIT -> 1
                LootKind.GRENADE -> grenValue
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
        if (teams) p.team = players.size % 2
        // TDM / Gun Game / Warfare / round modes are competitive arenas: spawn
        // on a town with a kit. Resurgence keeps the royale skydive but comes back.
        val competitive = (respawnMode && !resurgence) || roundMode || warfare
        if (competitive) {
            val s = spawnSpot(p)
            p.ctrl.teleport(s[0], s[1], s[2])
            p.skydiving = false
            p.parachute = false
            p.spawnGuard = 3f
            if (roundMode) p.cash = 1000
            if (gunGame) equipGunGameLoadout(p)
            else if (roundMode) equipRoundKit(p)
            else equipTeamLoadout(p)
        } else {
            // Battle Royale and survival modes begin with the readable skydive
            // drop; the player earns the loadout from the island.
            val a = rng.range(0f, MathX.TAU); val d = sqrt(rng.nextFloat()) * ArenaMap.SIZE * 0.32f
            val x = sin(a) * d; val z = cos(a) * d
            p.ctrl.teleport(x, map.heightAt(x, z) + 85f + rng.range(0f, 15f), z)
        }
        p.yaw = rng.range(0f, MathX.TAU)
        p.input.yaw = p.yaw
        if (bot) p.bot = BotBrain(p, Rng(seed xor id.toLong() * 7919L), botSkill).also { it.attach(map.physics) }
        players[id] = p
        return p
    }

    /** Ground spawn point: the team's town in TDM, a random town otherwise. */
    private fun spawnSpot(p: ArenaPlayer): FloatArray {
        val towns = map.towns
        val t = when {
            towns.isEmpty() -> floatArrayOf(0f, 0f)
            teams -> towns[p.team % towns.size]
            else -> towns[rng.nextInt(towns.size)]
        }
        val x = t[0] + rng.range(-6f, 6f); val z = t[1] + rng.range(-6f, 6f)
        return floatArrayOf(x, map.heightAt(x, z) + 0.1f, z)
    }

    private fun equipGunGameLoadout(p: ArenaPlayer) {
        val w = GUN_LADDER[p.gunLevel.coerceIn(0, GUN_LADDER.lastIndex)]
        p.ammo.fill(0)
        p.mags.fill(0)
        p.slots[0] = w
        p.slots[1] = Weapon.PAN
        p.mags[0] = w.mag
        p.active = 0
        p.medkits = 0
        if (w.ammo >= 0) p.ammo[w.ammo] = w.mag * 2
    }

    private fun equipTeamLoadout(p: ArenaPlayer) {
        p.ammo.fill(0)
        p.mags.fill(0)
        p.slots[0] = Weapon.SMG
        p.slots[1] = Weapon.PISTOL
        p.mags[0] = Weapon.SMG.mag
        p.mags[1] = Weapon.PISTOL.mag
        p.active = 0
        p.ammo[0] = 90
        p.ammo[1] = 48
        p.medkits = 1
        p.grenades = 1
    }

    /** Round-mode starting kit: a pistol, two medkits, a frag and a smoke —
     *  the shop refines it before every round. */
    private fun equipRoundKit(p: ArenaPlayer) {
        p.ammo.fill(0)
        p.mags.fill(0)
        p.slots[0] = Weapon.PISTOL
        p.slots[1] = Weapon.PAN
        p.mags[0] = Weapon.PISTOL.mag
        p.active = 0
        p.ammo[0] = 60
        p.medkits = 2
        p.grenades = 1
        p.smokes = 1
        p.armor = 1
        p.helmet = 1
        applyUpgrades(p) // keep upgraded mags after the round restarts
    }

    /** Start (or restart) a round: everyone respawns at their team's area, kit applied. */
    fun startRound() {
        roundPhase = 0
        for (p in players.values) {
            p.alive = true
            p.health = 100f
            p.healProgress = 0f
            p.reloadLeft = 0f
            p.stunTimer = 0f
            p.skydiving = false
            p.parachute = false
            p.cooldown = 0f
            p.switchLeft = 0f
            p.spread = 0f
            p.recoil = 0f
            val s = spawnSpot(p)
            p.ctrl.teleport(s[0], s[1], s[2])
            p.ctrl.velocity.setZero()
        }
        if (roundMode) events?.onRoundStart(round)
    }

    /** Clash Squad shop: spend the player's cash on weapons and consumables. */
    private fun applyBuys(p: ArenaPlayer, inp: PlayerInput) {
        if (inp.buySlot >= 0) {
            val w = Weapon.entries.getOrNull(inp.buySlot)
            // Wolf picks are free (the duel is about the gun, not the budget).
            if (w != null && w != Weapon.PAN && (w.price <= p.cash || rules == "wolf")) {
                p.cash -= w.price
                p.slots[0] = w
                p.mags[0] = w.mag
                p.active = 0
                p.ammo.fill(0)
                if (w.ammo >= 0) p.ammo[w.ammo] = w.mag * 3
            }
            inp.buySlot = -1
        }
        if (inp.buyMedkits > 0 && p.cash >= SHOP_MEDKIT) { p.medkits += inp.buyMedkits; p.cash -= SHOP_MEDKIT * inp.buyMedkits; inp.buyMedkits = 0 }
        if (inp.buyFrags > 0 && p.cash >= SHOP_FRAG) { p.grenades += inp.buyFrags; p.cash -= SHOP_FRAG * inp.buyFrags; inp.buyFrags = 0 }
        if (inp.buySmokes > 0 && p.cash >= SHOP_SMOKE) { p.smokes += inp.buySmokes; p.cash -= SHOP_SMOKE * inp.buySmokes; inp.buySmokes = 0 }
        if (inp.buyStuns > 0 && p.cash >= SHOP_STUN) { p.stuns += inp.buyStuns; p.cash -= SHOP_STUN * inp.buyStuns; inp.buyStuns = 0 }
        if (inp.buyVest > 0 && p.armor < 3 && p.cash >= SHOP_VEST) { p.armor++; p.cash -= SHOP_VEST; inp.buyVest = 0 }
        if (inp.buyHelmet > 0 && p.helmet < 3 && p.cash >= SHOP_HELMET) { p.helmet++; p.cash -= SHOP_HELMET; inp.buyHelmet = 0 }
    }

    /** The bots' shop run: a gun the budget allows, then support items — once per prep. */
    private fun botBuy(p: ArenaPlayer) {
        if (p.slots[0] != Weapon.PISTOL) return // already shopped this prep
        val pool = Weapon.lootPool(loadout)
        val affordable = pool.filter { it.price <= p.cash }
        val w = affordable.let { if (it.isEmpty()) return else it[rng.nextInt(it.size)] }
        p.cash -= w.price
        p.slots[0] = w
        p.mags[0] = w.mag
        p.ammo.fill(0)
        if (w.ammo >= 0) p.ammo[w.ammo] = w.mag * 3
        if (p.cash > 500) { p.medkits++; p.cash -= SHOP_MEDKIT }
        if (p.cash > 700) { p.grenades++; p.cash -= SHOP_FRAG }
        if (inp.buyUpSlot >= 0) {
            Weapon.entries.getOrNull(inp.buyUpSlot)?.let { if (upgrade(p, it)) inp.buyUpSlot = -1 }
        }
        if (p.cash > 900) { p.smokes++; p.cash -= SHOP_SMOKE }
        p.armor = min(3, p.armor + 1)
        p.helmet = min(3, p.helmet + 1)
    }

    private fun respawn(p: ArenaPlayer) {
        if (resurgence) {
            // Royale resurgence: the kit survives the death; only position and health change.
            val s = spawnSpot(p)
            p.ctrl.teleport(s[0], s[1], s[2])
            p.ctrl.velocity.setZero()
            p.health = 100f
            p.alive = true
            p.skydiving = false
            p.parachute = false
            p.healProgress = 0f
            p.reloadLeft = 0f
            p.cooldown = 0f
            p.switchLeft = 0f
            p.respawnAt = 0f
            p.spawnGuard = 3f
            p.spread = 0f
            p.recoil = 0f
            return
        }
        val s = spawnSpot(p)
        p.ctrl.teleport(s[0], s[1], s[2])
        p.ctrl.velocity.setZero()
        p.health = 100f
        p.armor = if (teams) 1 else 0
        p.helmet = 0
        p.alive = true
        p.skydiving = false
        p.parachute = false
        p.healProgress = 0f
        p.reloadLeft = 0f
        p.cooldown = 0f
        p.switchLeft = 0f
        p.respawnAt = 0f
        p.spawnGuard = 3f
        p.spread = 0f
        p.recoil = 0f
        if (gunGame) equipGunGameLoadout(p) else equipTeamLoadout(p)
    }

    fun step(dt: Float) {
        if (over) return
        time += dt
        for (p in players.values) {
            if (p.spawnGuard > 0f) p.spawnGuard -= dt
            if (p.stunTimer > 0f) p.stunTimer -= dt
            if (!p.alive) {
                if (respawnMode && p.respawnAt > 0f && time >= p.respawnAt) respawn(p)
                continue
            }
            if (roundPhase == 2 && p.isBot) { botBuy(p); continue }
            if (roundPhase == 0) p.bot?.think(this, dt)
            applyInput(p, dt)
            if (roundPhase == 0) p.history.record((time * 1000f).toLong(), p.pos.x, p.pos.y, p.pos.z)
        }
        if (zone.enabled && zone.update(dt)) events?.onZone(zone.phase, zone.shrinking)
        if (zone.enabled) for (p in players.values) if (p.alive && !p.skydiving && zone.outside(p.pos.x, p.pos.z)) damage(p, null, zone.dps * dt, Weapon.PAN, false, true)
        // Grenades: arc on gravity, bounce once off the terrain, then go off on the fuse.
        // Rockets and guided missiles fly straight (or steer) instead of arcing.
        for (g in grenades) {
            if (g.dead) continue
            if (g.rocket || g.missile) {
                g.life -= dt
                if (g.missile) {
                    // Homing: steer toward the locked player — or re-lock the nearest
                    // enemy in front. This is the guided missile's whole trick.
                    var t = g.homing >= 0 ? players[g.homing] : null
                    if (t == null || !t.alive) {
                        var best = -1; var bestD = 75f * 75f
                        for (o in players.values) {
                            if (!o.alive || o.skydiving) continue
                            if (teams && o.team == g.team) continue
                            val ox = o.pos.x - g.x; val oz = o.pos.z - g.z
                            if (ox * ox + oz * oz > bestD) continue
                            // Only lock forward along the flight direction.
                            val fwd = g.vx; val right = g.vz
                            if (ox * fwd + oz * right < 0f) continue
                            val d2 = ox * ox + oz * oz
                            if (d2 < bestD) { bestD = d2; best = o.id }
                        }
                        g.homing = best
                    }
                    t?.let {
                        val tx = it.pos.x - g.x; val ty = it.pos.y + 1.0f - g.y; val tz = it.pos.z - g.z
                        val l = sqrt(tx * tx + ty * ty + tz * tz) + 1e-4f
                        val turn = 4.5f * dt
                        g.vx += (tx / l - g.vx) * turn; g.vy += (ty / l - g.vy) * turn; g.vz += (tz / l - g.vz) * turn
                        val nl = sqrt(g.vx * g.vx + g.vy * g.vy + g.vz * g.vz) + 1e-4f
                        g.vx = g.vx / nl * 45f; g.vy = g.vy / nl * 45f; g.vz = g.vz / nl * 45f
                    }
                    g.vy -= 8f * dt // slight settle, still mostly flying
                }
                g.x += g.vx * dt; g.y += g.vy * dt; g.z += g.vz * dt
                var blast = false
                if (g.y < map.heightAt(g.x, g.z)) blast = true
                if (g.life <= 0f) blast = true
                if (!blast) for (o in players.values) {
                    if (!o.alive) continue
                    val dx = o.pos.x - g.x; val dz = o.pos.z - g.z; val dy = o.pos.y + 1f - g.y
                    if (dx * dx + dz * dz + dy * dy < 1.4f * 1.4f) { blast = true; break }
                }
                if (!blast) for (v in vehicles) {
                    if (v.dead) continue
                    val dx = v.x - g.x; val dz = v.z - g.z; val dy = v.y + 1.2f - g.y
                    if (dx * dx + dz * dz + dy * dy < 2.6f * 2.6f) { blast = true; break }
                }
                if (blast) explode(g)
                continue
            }
            g.vy -= 21f * dt
            g.x += g.vx * dt; g.y += g.vy * dt; g.z += g.vz * dt
            val gy = map.heightAt(g.x, g.z)
            if (g.y < gy) {
                g.y = gy; g.vy = -g.vy * 0.35f; g.vx *= 0.55f; g.vz *= 0.55f
                g.bounces++
                if (g.bounces >= 1) g.fuse = min(g.fuse, 0.6f)
            }
            g.vx *= (1f - 1.2f * dt); g.vz *= (1f - 1.2f * dt)
            g.fuse -= dt
            if (g.fuse <= 0f) explode(g)
        }
        grenades.removeAll { it.dead }
        // Vehicles: drive, carry the driver, ram enemies, respawn in warfare.
        for (v in vehicles) {
            if (v.dead) {
                if (warfare && v.respawnAt > 0f && time >= v.respawnAt) {
                    v.dead = false; v.health = v.maxHealth; v.driver = -1; v.speedNow = 0f
                    // A helo must not respawn floating where it died.
                    v.y = map.heightAt(v.x, v.z)
                }
                continue
            }
            val d = v.driver >= 0 ? players[v.driver] : null
            if (d == null) { v.speedNow = 0f; continue }
            v.yaw += d.input.moveX * 2.4f * dt
            val throttle = d.input.moveY.coerceIn(-1f, 1f)
            if (v.kind == 3) {
                // Helo: the stick climbs/dives (gravity-assisted), the craft
                // always flies forward; terrain keeps it from going under.
                v.speedNow = v.speed
                v.y = (v.y + d.input.moveY * 9f * dt).coerceIn(map.heightAt(v.x, v.z) + 2f, 55f)
                v.x += sin(v.yaw) * v.speed * dt
                v.z += cos(v.yaw) * v.speed * dt
                v.x = v.x.coerceIn(0f, ArenaMap.SIZE); v.z = v.z.coerceIn(0f, ArenaMap.SIZE)
                d.pos.x = v.x; d.pos.z = v.z; d.pos.y = v.y + 1.3f; d.yaw = v.yaw
            } else {
                v.speedNow = v.speed * throttle
                v.x += sin(v.yaw) * v.speedNow * dt
                v.z += cos(v.yaw) * v.speedNow * dt
                v.x = v.x.coerceIn(0f, ArenaMap.SIZE); v.z = v.z.coerceIn(0f, ArenaMap.SIZE)
                v.y = map.heightAt(v.x, v.z)
                d.pos.x = v.x; d.pos.z = v.z; d.pos.y = v.y; d.yaw = v.yaw
            }
            // Ramming: fast vehicles hurt players they clip. A helo only ram
            // hugs the ground — at altitude it flies over everyone.
            if (abs(v.speedNow) > 4f && (v.kind != 3 || v.y - map.heightAt(v.x, v.z) < 3f)) {
                for (o in players.values) {
                    if (!o.alive || o === d || o.skydiving) continue
                    if (teams && o.team == d.team) continue
                    val dx = o.pos.x - v.x; val dz = o.pos.z - v.z
                    if (dx * dx + dz * dz < v.radius * v.radius) {
                        val spd = abs(v.speedNow) / v.speed
                        damage(o, d, 45f * spd, Weapon.PAN, false, cause = -3)
                    }
                }
            }
        }
        // Smoke clouds linger for a while — they hide whoever stands in them.
        for (s in smokes) s[3] -= dt
        smokes.removeAll { it[3] <= 0f }
        // Round modes: a wiped team ends the round; cash settles and the shop follows.
        if (roundMode && !over && roundPhase == 0) {
            val t0 = players.values.count { it.alive && it.team == 0 }
            val t1 = players.values.count { it.alive && it.team == 1 }
            if (t0 == 0 || t1 == 0) {
                val winTeam = if (t0 == 0) 1 else 0
                roundsWon[winTeam]++
                roundPhase = 1
                round++
                events?.onRoundOver(winTeam, round)
                for (p in players.values) p.cash += if (p.team == winTeam) 300 else 100
                if (roundsWon[winTeam] >= roundsToWin || round > 7) {
                    over = true
                    val w = players.values.maxByOrNull { it.kills + (if (it.team == winTeam) 1000 else 0) } ?: players.values.first()
                    w.place = 1
                    winner = w
                    events?.onMatchOver(w)
                }
            }
        }
        // Warfare: standing on an objective fills the capture bar for your team.
        if (warfare && !over) {
            for (pt in points) {
                var attackers = 0; var attackTeam = -1; var holders = 0
                for (p in players.values) {
                    if (!p.alive) continue
                    val dx = p.pos.x - pt.x; val dz = p.pos.z - pt.z
                    if (dx * dx + dz * dz < pt.r * pt.r) {
                        if (pt.owner == p.team) holders++
                        else { attackers++; attackTeam = p.team }
                    }
                }
                if (attackers > 0 && attackers >= holders) {
                    pt.progress = min(1f, pt.progress + dt * 0.9f)
                    if (pt.progress >= 1f) {
                        pt.owner = attackTeam
                        pt.progress = 0f
                        captures[attackTeam]++
                        events?.onPointCaptured(pt.x, pt.z, attackTeam)
                        if (!koth && captures[attackTeam] >= WARFARE_WIN) {
                            over = true
                            val w = players.values.maxByOrNull { it.kills + (if (it.team == attackTeam) 1000 else 0) } ?: players.values.first()
                            w.place = 1
                            winner = w
                            events?.onMatchOver(w)
                        }
                    }
                } else if (holders > 0) {
                    pt.progress = max(0f, pt.progress - dt * 0.5f)
                }
                // King of the Hill: the team that owns the hill scores while it
                // holds it uncontested — contested holds score nothing, the
                // capture bar decides who takes the hill.
                if (koth && holders > 0) {
                    kothScore[pt.owner] += dt * KOTH_RATE
                    if (kothScore[pt.owner] >= KOTH_WIN) {
                        over = true
                        val w = players.values.maxByOrNull { it.kills + (if (it.team == pt.owner) 1000 else 0) } ?: players.values.first()
                        w.place = 1
                        winner = w
                        events?.onMatchOver(w)
                    }
                }
            }
        }
        if (!over && teams && (teamScore[0] >= TEAM_SCORE_WIN || teamScore[1] >= TEAM_SCORE_WIN)) {
            over = true
            val winning = if (teamScore[0] >= teamScore[1]) 0 else 1
            winner = players.values.filter { it.team == winning }.maxByOrNull { it.kills }
            winner?.place = 1
            events?.onMatchOver(winner)
        } else if (!over && !respawnMode && !roundMode && players.size > 1 && aliveCount <= 1) {
            over = true
            winner = players.values.firstOrNull { it.alive }
            winner?.place = 1
            events?.onMatchOver(winner)
        } else if (!over && survival && players.values.none { !it.isBot && it.alive }) {
            // Every human is out: the waves win the island.
            over = true
            winner = players.values.filter { it.alive }.maxByOrNull { it.kills }
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
        p.crouching = inp.crouch && !inp.prone
        p.prone = inp.prone
        p.aiming = inp.ads && p.weapon != Weapon.PAN
        val sprint = my > 0.92f && !p.aiming && !p.crouching && !p.prone && !inp.fire
        // Heavier guns slow you down whether hip-firing, sprinting or aiming;
        // the held weapon is part of the movement balance, not only an ADS penalty.
        val weight = p.weapon.moveSpeed
        // A stun grenade's flash crawls the legs; prone is the slowest, steadiest stance.
        val speed = when {
            p.stunTimer > 0f -> (if (p.crouching) 2.6f else 5.4f) * weight * 0.45f
            p.prone -> 1.8f * weight
            p.crouching -> 2.6f * weight
            p.aiming -> 3.4f * weight
            sprint -> 7.0f * weight
            else -> 5.4f * weight
        }
        p.ctrl.height = when {
            p.prone -> 0.65f
            p.crouching -> 1.2f
            else -> 1.8f
        }
        // Vault assist: jumping while pressed against an obstacle (a low wall,
        // a crate, a window sill) gives extra lift so the jump actually clears
        // it instead of bouncing off — this is what makes windows and ledges
        // climbable rather than decorative. Only applies from the ground, so
        // it cannot be chained into an infinite climb.
        val vaultBoost = if (p.ctrl.hitWall && p.ctrl.grounded) 2.6f else 0f
        p.ctrl.move(map.physics, dirX * speed, dirZ * speed, if (inp.jump && !p.crouching) 7.4f + vaultBoost else 0f, dt)
        return true
    }

    private fun applyInput(p: ArenaPlayer, dt: Float) {
        val inp = p.input
        // Prep / round-over (round modes): only shop purchases and the deploy command matter.
        if (roundPhase != 0) {
            if (roundPhase == 2) {
                applyBuys(p, inp)
                if (inp.deployRound) { startRound(); inp.deployRound = false }
            } else if (inp.advancePrep) {
                roundPhase = 2
                inp.advancePrep = false
            }
            return
        }
        // Vehicles: mount when close, dismount on press; tanks and turrets fire their own weapons.
        if (inp.mount) {
            inp.mount = false
            if (p.vehicle >= 0) unmount(p) else tryMount(p)
        }
        if (p.vehicle >= 0) {
            vehicleFire(p, inp, dt)
            return
        }
        if (!move(p, inp, dt)) return
        val len = sqrt(inp.moveX * inp.moveX + inp.moveY * inp.moveY)
        // Weapons.
        if (p.cooldown > 0f) p.cooldown -= dt
        if (p.switchLeft > 0f) p.switchLeft -= dt
        if (gunGame) {
            // Gun Game: your gun is your ladder stage — no slot switching.
            val lw = GUN_LADDER[p.gunLevel]
            if (p.slots[0] != lw) {
                p.slots[0] = lw; p.slots[1] = Weapon.PAN; p.mags[0] = lw.mag; p.mags[1] = 0; p.active = 0
                p.reloadLeft = 0f; p.switchLeft = 0.35f
                if (lw.ammo >= 0) p.ammo[lw.ammo] = lw.mag * 2
            }
            inp.slot = -1
        } else if (inp.slot in 0..1 && inp.slot != p.active) { p.active = inp.slot; p.switchLeft = 0.45f; p.reloadLeft = 0f }
        val w = p.weapon
        if (p.reloadLeft > 0f) {
            p.reloadLeft -= dt
            if (p.reloadLeft <= 0f && w.ammo >= 0) {
                val emag = ceil(w.mag * (1f + 0.1f * p.upLevels[w.ordinal])).toInt()
                val need = emag - p.mags[p.active]
                val take = min(need, p.ammo[w.ammo])
                p.mags[p.active] += take; p.ammo[w.ammo] -= take
            }
        } else if (w.ammo >= 0 && (inp.reload || p.mags[p.active] == 0 && inp.fire) && p.mags[p.active] < ceil(w.mag * (1f + 0.1f * p.upLevels[w.ordinal])) && p.ammo[w.ammo] > 0) {
            p.reloadLeft = w.reload * (1f - 0.08f * p.upLevels[w.ordinal]) // upgrades reload faster
        }
        val moving = len > 0.2f
        // Prone is the steadiest stance; a stun opens the spread right up.
        val stance = if (p.prone) 0.55f else if (p.crouching) 0.7f else 1f
        val target = (if (p.aiming) w.spreadAds else w.spreadHip) * (if (moving) 1.6f else 1f) * (if (!p.ctrl.grounded) 2.2f else 1f) * stance * (if (p.stunTimer > 0f) 1.8f else 1f)
        p.spread = MathX.damp(p.spread, target, 8f, dt)
        if (inp.fire && p.cooldown <= 0f && p.reloadLeft <= 0f && p.switchLeft <= 0f && p.stunTimer <= 0f) {
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
        // One-shot throws: frag (with its cook time), smoke, or stun.
        if (inp.grenade) { throwGrenade(p, GRENADE_FRAG, inp.cook); inp.grenade = false; inp.cook = 0f }
        if (inp.smoke) { throwGrenade(p, GRENADE_SMOKE); inp.smoke = false }
        if (inp.stun) { throwGrenade(p, GRENADE_STUN); inp.stun = false }
        if (inp.reloadAll) { reloadAll(p); inp.reloadAll = false }
    }

    /** Climb into the nearest empty vehicle. */
    private fun tryMount(p: ArenaPlayer) {
        var best: Vehicle? = null; var bestD = 4f * 4f
        for (v in vehicles) {
            if (v.dead || v.driver >= 0) continue
            // A flying helo can't be boarded mid-air.
            if (v.isHelo && v.y - map.heightAt(v.x, v.z) > 2f) continue
            val dx = v.x - p.pos.x; val dz = v.z - p.pos.z
            val d2 = dx * dx + dz * dz
            if (d2 < bestD) { bestD = d2; best = v }
        }
        best?.let {
            it.driver = p.id; p.vehicle = it.id
            p.pos.x = it.x; p.pos.z = it.z; p.pos.y = it.y; p.yaw = it.yaw
        }
    }

    /** Step out of a vehicle, keeping its position. */
    private fun unmount(p: ArenaPlayer) {
        val v = vehicles.getOrNull(p.vehicle) ?: run { p.vehicle = -1; return }
        if (v.driver == p.id) { v.driver = -1; v.speedNow = 0f }
        p.vehicle = -1
        p.pos.x = v.x; p.pos.z = v.z; p.pos.y = v.y; p.yaw = v.yaw
    }

    /** Vehicle weapons: the tank's 105 mm splash cannon and the turret's lock-on missiles. */
    private fun vehicleFire(p: ArenaPlayer, inp: PlayerInput, dt: Float) {
        val v = vehicles.getOrNull(p.vehicle) ?: run { p.vehicle = -1; return }
        if (v.dead) { p.vehicle = -1; return }
        if (p.cooldown > 0f) p.cooldown -= dt
        if (inp.fire && p.cooldown <= 0f) {
            val cp = cos(p.pitch)
            val dx = sin(p.yaw) * cp; val dy = sin(p.pitch); val dz = cos(p.yaw) * cp
            if (v.isTank) {
                p.cooldown = 1.6f
                grenades += Grenade(v.x, v.y + 2.4f, v.z, dx * 65f, dy * 65f, dz * 65f, GRENADE_FRAG, rocket = true, team = p.team)
            } else if (v.isTurret) {
                p.cooldown = 2.0f
                grenades += Grenade(v.x, v.y + 2.0f, v.z, dx * 40f, dy * 40f, dz * 40f, GRENADE_FRAG, missile = true, homing = -1, team = p.team)
            }
        }
    }

    /** Reload-all: top up every weapon in the pack from the reserves, instantly. */
    fun reloadAll(p: ArenaPlayer) {
        for (slot in 0..1) {
            val w = p.slots[slot]
            if (w == Weapon.PAN || w.ammo < 0) continue
            val need = w.mag - p.mags[slot]
            if (need <= 0) continue
            val take = min(need, p.ammo[w.ammo])
            p.mags[slot] += take; p.ammo[w.ammo] -= take
        }
    }

    private fun fire(p: ArenaPlayer) {
        val w = p.weapon
        // Launchers fire a warhead instead of a bullet.
        if (w == Weapon.ROCKET || w == Weapon.MISSILE) { launch(p, w == Weapon.ROCKET); return }
        p.cooldown = w.interval
        p.shots++
        p.lastShotTime = time
        if (w != Weapon.PAN) p.mags[p.active]--
        val ex = p.pos.x; val ey = p.pos.y + p.eyeHeight; val ez = p.pos.z
        val cp = cos(p.pitch)
        val bx = sin(p.yaw) * cp; val by = sin(p.pitch); val bz = cos(p.yaw) * cp
        val shotTime = (time * 1000f).toLong()
        val upMul = 1f + 0.1f * p.upLevels[w.ordinal] // weapon upgrade damage bonus
        for (k in 0 until w.pellets) {
            var dx = bx + rng.gaussian() * p.spread * 0.5f
            var dy = by + rng.gaussian() * p.spread * 0.5f
            var dz = bz + rng.gaussian() * p.spread * 0.5f
            val l = sqrt(dx * dx + dy * dy + dz * dz); dx /= l; dy /= l; dz /= l
            var dist = w.range
            var hitWorld = false
            if (map.physics.raycast(ex, ey, ez, dx, dy, dz, w.range, hit, Layers.WORLD or Layers.PROPS)) { dist = hit.distance; hitWorld = true }
            // Vehicles sit on the ray and swallow the shot before players are tested.
            var hitVehicle: Vehicle? = null
            for (v in vehicles) {
                if (v.dead) continue
                if (teams && v.driver >= 0) {
                    val drv = players[v.driver]
                    if (drv != null && drv.team == p.team) continue
                }
                val dxv = v.x - ex; val dyv = (v.y + 1.0f) - ey; val dzv = v.z - ez
                val t = dxv * dx + dyv * dy + dzv * dz
                if (t < 0f || t > w.range) continue
                val px = ex + dx * t; val py = ey + dy * t; val pz = ez + dz * t
                val dd = sqrt((px - v.x) * (px - v.x) + (py - (v.y + 1f)) * (py - (v.y + 1f)) + (pz - v.z) * (pz - v.z))
                if (dd < v.radius + 0.3f && t < dist) { dist = t; hitVehicle = v }
            }
            var victim: ArenaPlayer? = null
            var head = false
            if (hitVehicle == null) {
                for (o in players.values) {
                    if (o === p || !o.alive || o.skydiving) continue
                    if (teams && o.team == p.team) continue // friendly fire is off
                    // Lag compensation: test where the shooter saw the target.
                    val rewindMs = p.bot?.let { 0L } ?: compensation(p)
                    if (!o.history.sample(shotTime - rewindMs, rewind)) rewind.set(o.pos)
                    val oy = if (o.crouching) 1.05f else 1.6f
                    val th = Intersect.raySphere(ex, ey, ez, dx, dy, dz, rewind.x, rewind.y + oy + 0.02f, rewind.z, 0.24f)
                    val tb = Intersect.rayCapsule(ex, ey, ez, dx, dy, dz, rewind.x, rewind.y + 0.3f, rewind.z, rewind.x, rewind.y + oy - 0.3f, rewind.z, 0.36f)
                    val t = when { th >= 0f && (tb < 0f || th <= tb) -> th; else -> tb }
                    if (t in 0f..dist && !inSmoke(ex, ey, ez, dx, dy, dz, t)) { dist = t; victim = o; head = t == th }
                }
            }
            val hx = ex + dx * dist; val hy = ey + dy * dist; val hz = ez + dz * dist
            if (hitVehicle != null) {
                damageVehicle(hitVehicle, w.damage * upMul * (1f - 0.35f * (hitVehicle.maxHealth - hitVehicle.health) / hitVehicle.maxHealth), p)
                events?.onShot(p, hx, hy, hz, false)
            } else if (victim != null) {
                val falloff = if (dist > w.range * 0.5f) MathX.lerp(1f, 0.6f, (dist - w.range * 0.5f) / (w.range * 0.5f)) else 1f
                val dmg = w.damage * upMul * falloff * (if (head) w.headshot else 1f)
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

    fun damage(v: ArenaPlayer, from: ArenaPlayer?, amount: Float, w: Weapon, head: Boolean, zoneDamage: Boolean = false, cause: Int = -1) {
        if (!v.alive) return
        // Spawn protection: freshly respawned players get a few honest seconds.
        if (!zoneDamage && v.spawnGuard > 0f) return
        if (!zoneDamage && teams && from != null && from.team == v.team) return
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
            v.deaths++
            v.killedBy = from?.id ?: cause
            from?.let { it.kills++; if (roundMode || warfare) it.cash += 150 }
            if (teams && from != null && from.team != v.team) teamScore[from.team]++
            if (gunGame) {
                if (from != null) {
                    if (from.gunLevel >= GUN_LADDER.size - 1 && !over) {
                        // A kill with the final stage (the pan) wins the ladder.
                        over = true
                        winner = from
                        from.place = 1
                        events?.onMatchOver(from)
                    } else {
                        from.gunLevel = (from.gunLevel + 1).coerceAtMost(GUN_LADDER.size - 1)
                    }
                }
                v.gunLevel = (v.gunLevel - 1).coerceAtLeast(0)
                v.respawnAt = time + 1.5f
            } else if (teams) {
                v.place = 0
                v.respawnAt = time + 4f
            } else if (resurgence) {
                v.place = aliveCount + 1
                dropLoot(v)
                // CoD-style royale: a 20-second comeback window, then you're back on a town.
                v.respawnAt = time + 20f
            } else {
                v.place = aliveCount + 1
                dropLoot(v)
            }
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
            LootKind.GRENADE -> when (l.value) {
                GRENADE_FRAG -> p.grenades++
                GRENADE_SMOKE -> p.smokes++
                else -> p.stuns++
            }
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

    /** Throws one grenade of the given type (0 frag, 1 smoke, 2 stun) along the aim.
     *  `cook` shortens the fuse (hold-to-cook): the longer held, the sooner it goes off. */
    fun throwGrenade(p: ArenaPlayer, type: Int, cook: Float = 0f) {
        if (type == GRENADE_FRAG) { if (p.grenades <= 0) return; p.grenades-- }
        else if (type == GRENADE_SMOKE) { if (p.smokes <= 0) return; p.smokes-- }
        else { if (p.stuns <= 0) return; p.stuns-- }
        val cp = cos(p.pitch)
        val dx = sin(p.yaw) * cp; val dy = sin(p.pitch); val dz = cos(p.yaw) * cp
        val power = 9.5f
        grenades += Grenade(p.pos.x, p.pos.y + 1.35f, p.pos.z, dx * power, dy * power + 4.2f, dz * power, type, fuse = max(0.7f, 2.4f - cook * 1.6f))
    }

    /** Frag: a 4.6 m damage radius. Smoke: a cloud. Stun: a flash that slows everyone in range. */
    private fun explode(g: Grenade) {
        g.dead = true
        if (g.type == GRENADE_STUN) {
            events?.onGrenade(g.x, g.y, g.z, GRENADE_STUN)
            val r = 3.2f
            for (p in players.values) {
                if (!p.alive) continue
                val dx = p.pos.x - g.x; val dz = p.pos.z - g.z; val dy = p.pos.y + 1.0f - g.y
                val d = sqrt(dx * dx + dy * dy + dz * dz)
                if (d < r) p.stunTimer = max(p.stunTimer, 3f)
            }
            return
        }
        if (g.type == GRENADE_SMOKE) {
            smokes += floatArrayOf(g.x, g.y, g.z, 10f)
            events?.onGrenade(g.x, g.y, g.z, GRENADE_SMOKE)
            return
        }
        val big = g.rocket || g.missile
        val r = if (big) (if (g.rocket) 4.5f else 3.5f) else 4.6f
        val dmg = if (big) (if (g.rocket) 140f else 120f) else 80f
        for (p in players.values) {
            if (!p.alive) continue
            val dx = p.pos.x - g.x; val dz = p.pos.z - g.z; val dy = p.pos.y + 1.0f - g.y
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d < r) {
                val falloff = 1f - d / r
                damage(p, null, dmg * falloff * falloff, Weapon.PAN, false, true, cause = -2)
            }
        }
        for (v in vehicles) {
            if (v.dead) continue
            val dx = v.x - g.x; val dz = v.z - g.z; val dy = v.y + 1.2f - g.y
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d < r) {
                val falloff = 1f - d / r
                damageVehicle(v, dmg * falloff * falloff, null)
            }
        }
        events?.onGrenade(g.x, g.y, g.z, if (big) GRENADE_ROCKET else GRENADE_FRAG)
    }

    /** Rocket launchers and guided missiles: straight-flying warheads, no arc. */
    private fun launch(p: ArenaPlayer, rocket: Boolean) {
        if (p.mags[p.active] <= 0) return
        p.mags[p.active]--
        p.cooldown = if (rocket) Weapon.ROCKET.interval else Weapon.MISSILE.interval
        p.shots++
        p.lastShotTime = time
        val cp = cos(p.pitch)
        val dx = sin(p.yaw) * cp; val dy = sin(p.pitch); val dz = cos(p.yaw) * cp
        val speed = if (rocket) 55f else 45f
        val g = Grenade(p.pos.x, p.pos.y + p.eyeHeight, p.pos.z, dx * speed, dy * speed, dz * speed,
            GRENADE_FRAG, rocket = rocket, missile = !rocket, homing = -1, life = 10f, team = p.team)
        grenades += g
    }

    /** Destroy a vehicle: the crew dies with it, and the wreck takes everything nearby with it. */
    fun damageVehicle(v: Vehicle, amount: Float, from: ArenaPlayer?) {
        if (v.dead) return
        v.health -= amount
        if (v.health > 0f) return
        v.dead = true
        if (v.driver >= 0) {
            val d = players[v.driver]
            if (d != null && d.alive) damage(d, from, 100f, Weapon.PAN, false)
        }
        v.driver = -1
        val r = 5f
        for (o in players.values) {
            if (!o.alive) continue
            val dx = o.pos.x - v.x; val dz = o.pos.z - v.z; val dy = o.pos.y + 1f - (v.y + 1.2f)
            val d = sqrt(dx * dx + dz * dz + dy * dy)
            if (d < r) damage(o, from, 90f * (1f - d / r) * (1f - d / r), Weapon.PAN, false, cause = -2)
        }
        for (o in vehicles) {
            if (o === v || o.dead) continue
            val dx = o.x - v.x; val dz = o.z - v.z
            val d = sqrt(dx * dx + dz * dz)
            if (d < r) o.health -= 90f * (1f - d / r) * (1f - d / r)
        }
        events?.onGrenade(v.x, v.y + 1.2f, v.z, GRENADE_ROCKET)
        if (warfare) v.respawnAt = time + 12f
        else vehicles.remove(v)
    }

    /** Weapon upgrade cost for the player's next level (Clash Squad cash). */
    fun upgradeCost(p: ArenaPlayer, w: Weapon): Int = UP_BASE * (1 + p.upLevels[w.ordinal])

    /** Spend cash to level a weapon up (max UP_MAX levels). */
    fun upgrade(p: ArenaPlayer, w: Weapon): Boolean {
        val lv = p.upLevels[w.ordinal]
        if (lv >= UP_MAX) return false
        val cost = upgradeCost(p, w)
        if (p.cash < cost) return false
        p.cash -= cost
        p.upLevels[w.ordinal] = lv + 1
        applyUpgrades(p)
        return true
    }

    /** Re-apply upgrade multipliers (mag size) to the equipped loadout. */
    private fun applyUpgrades(p: ArenaPlayer) {
        for (slot in 0..1) {
            val w = p.slots[slot]
            if (w == Weapon.PAN) continue
            val lv = p.upLevels[w.ordinal]
            p.mags[slot] = ceil(w.mag * (1f + 0.1f * lv)).toInt()
        }
    }

    fun lineOfSight(a: ArenaPlayer, b: ArenaPlayer): Boolean =
        map.physics.lineOfSight(a.pos.x, a.pos.y + a.eyeHeight, a.pos.z, b.pos.x, b.pos.y + 1.2f, b.pos.z, Layers.WORLD or Layers.PROPS, hit) &&
            !smokeBetween(a.pos.x, a.pos.y + a.eyeHeight, a.pos.z, b.pos.x, b.pos.y + 1.2f, b.pos.z)

    /** True when the point at distance t along the ray sits inside a smoke cloud. */
    private fun inSmoke(ex: Float, ey: Float, ez: Float, dx: Float, dy: Float, dz: Float, t: Float): Boolean {
        for (s in smokes) {
            val px = ex + dx * t; val py = ey + dy * t; val pz = ez + dz * t
            val dx2 = px - s[0]; val dy2 = py - (s[1] + 1.2f); val dz2 = pz - s[2]
            if (dx2 * dx2 + dy2 * dy2 + dz2 * dz2 < 1.6f * 1.6f) return true
        }
        return false
    }

    /** True when the segment passes through any active smoke cloud. */
    private fun smokeBetween(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Boolean {
        val segX = bx - ax; val segY = by - ay; val segZ = bz - az
        val segLen2 = segX * segX + segY * segY + segZ * segZ + 1e-6f
        for (s in smokes) {
            var t = ((s[0] - ax) * segX + (s[1] + 1.2f - ay) * segY + (s[2] - az) * segZ) / segLen2
            t = MathX.clamp(t, 0f, 1f)
            val px = ax + segX * t; val py = ay + segY * t; val pz = az + segZ * t
            val dx2 = px - s[0]; val dy2 = py - (s[1] + 1.2f); val dz2 = pz - s[2]
            if (dx2 * dx2 + dy2 * dy2 + dz2 * dz2 < 1.8f * 1.8f) return true
        }
        return false
    }

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
        val baseD = if (sim.gunGame) 80f else 60f
        var bestD = baseD + skill * 30f
        for (o in sim.players.values) {
            if (o === p || !o.alive || o.skydiving) continue
            if (sim.teams && o.team == p.team) continue
            val dx = o.pos.x - p.pos.x; val dz = o.pos.z - p.pos.z
            val d = sqrt(dx * dx + dz * dz)
            if (d > bestD) continue
            val ang = abs(MathX.angleDelta(p.yaw, atan2(dx, dz)))
            val heard = d < 14f || (sim.time - o.lastShotTime < 1f && d < 45f) || (p.lastDamageFrom == o.id && sim.time - p.lastDamageTime < 2f)
            if (ang > 1.2f && !heard) continue
            if (!sim.lineOfSight(p, o)) continue
            best = o; bestD = d
        }
        if (best != null && best !== target) {
            val extra = if (sim.gunGame) -0.08f else 0f
            reaction = MathX.lerp(0.75f, 0.18f, skill) + rng.range(0f, 0.2f) + extra
            aimError = MathX.lerp(0.45f, 0.12f, skill)
        }
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
        val ideal = when (w) {
            Weapon.SHOTGUN -> 6f
            Weapon.SMG -> 12f
            Weapon.SNIPER -> 45f
            Weapon.MARKSMAN -> 35f
            Weapon.REVOLVER -> 14f
            Weapon.LMG -> 25f
            else -> 20f
        }
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
        // Respawn modes: pressure the other side of the map instead of looting.
        if (sim.respawnMode && sim.map.towns.isNotEmpty()) {
            val towns = sim.map.towns
            val home = if (sim.teams) p.team % towns.size else -1
            val choices = towns.indices.filter { it != home }
            val t = towns[if (choices.isEmpty()) 0 else choices[rng.nextInt(choices.size)]]
            goalX = t[0] + rng.range(-12f, 12f); goalZ = t[1] + rng.range(-12f, 12f)
            hasGoal = true
            return
        }
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
