package com.oea.games.arena

import com.oea.engine.graphics.Material
import com.oea.engine.graphics.Mesh
import com.oea.engine.graphics.MeshBuilder
import com.oea.engine.graphics.Textures
import com.oea.engine.math.Colors
import com.oea.engine.math.MathX
import com.oea.engine.math.Noise
import com.oea.engine.math.Rng
import com.oea.engine.physics.HeightField
import com.oea.engine.physics.Layers
import com.oea.engine.physics.PhysicsWorld
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Seeded battle-royale island: terrain, towns with enterable houses, cover,
 * trees and loot spots. The terrain style is a real level, not a label: it
 * changes the landform (rolling green island, steep alpine highlands with
 * snowcaps, broken red-ridge badlands), the palette, the vegetation and the
 * water. Dirt roads connect the towns, and watchtowers overlook them.
 */
internal class ArenaMap(val seed: Long, private val terrainStyle: String = "island") {
    class Tree(val x: Float, val z: Float, val y: Float, val scale: Float, val tint: Int)
    class Rock(val x: Float, val y: Float, val z: Float, val sx: Float, val sy: Float, val sz: Float, val yaw: Float)
    class House(val x0: Float, val z0: Float, val x1: Float, val z1: Float, val floor: Float, val doorSide: Int)

    val physics = PhysicsWorld(8f)
    lateinit var terrain: HeightField
        private set
    val trees = ArrayList<Tree>()
    val rocks = ArrayList<Rock>()
    val houses = ArrayList<House>()
    val lootSpots = ArrayList<FloatArray>()
    val towns = ArrayList<FloatArray>()
    /** Town-to-town roads as (x0, z0, x1, z1) segments; used for terrain colouring. */
    val roads = ArrayList<FloatArray>()
    /** Static geometry merged per material (built on demand on the GL side). */
    lateinit var terrainMesh: Mesh
        private set
    lateinit var buildingMesh: Mesh
        private set
    lateinit var roofMesh: Mesh
        private set
    lateinit var propMesh: Mesh
        private set

    /** Materials tinted for this level style (shared meshes, per-level colours). */
    val terrainMaterial = Material(
        color = Colors.WHITE,
        texture = Textures.noise(256, if (terrainStyle == "badlands") 22f else 30f, 5, 0xFFBDBDBD.toInt(), 0xFFFFFFFF.toInt()),
        specular = 0.04f, uvScaleX = 1f, uvScaleY = 1f,
    )
    val wallMaterial = Material(
        color = Colors.WHITE,
        texture = Textures.noise(128, 12f, 6, 0xFFDDDDDD.toInt(), 0xFFFFFFFF.toInt()),
        specular = 0.1f,
    )
    val roofMaterial = Material(color = Colors.WHITE, specular = 0.2f, shininess = 30f)
    val propMaterial = Material(color = Colors.WHITE, specular = 0.35f, shininess = 40f)
    val water = Material(
        color = when (terrainStyle) {
            "highlands" -> 0xB32E6E8E.toInt()
            "badlands" -> 0xB33E7A8C.toInt()
            else -> 0xB32A86C8.toInt()
        },
        emissive = 0xFF0E3A5C.toInt(), emissiveStrength = 0.4f,
        specular = 1f, shininess = 150f, transparent = true, castShadows = false, receiveShadows = false,
    )
    val trunk = Material(color = (if (terrainStyle == "badlands") 0xFF7A5236 else 0xFF6B4A2E).toInt(), specular = 0.05f)
    val leaves = Material(color = Colors.WHITE, specular = 0.08f, rim = 0.15f)
    val rock = Material(
        color = when (terrainStyle) {
            "highlands" -> 0xFF8E9096.toInt()
            "badlands" -> 0xFF9A5B44.toInt()
            else -> 0xFF8C8A84.toInt()
        },
        specular = 0.15f,
    )
    /** Pine crowns for alpine/badlands, broadleaf crowns for the green island. */
    val crownMesh: Mesh get() = if (terrainStyle == "island") treeBroad else treeCrown

    init { generate() }

    fun heightAt(x: Float, z: Float) = terrain.heightAt(x, z)

    private fun generate() {
        val rng = Rng(seed)
        val noise = Noise(seed)
        val res = RES
        val cell = SIZE / (res - 1)
        val origin = -SIZE / 2f
        val heights = FloatArray(res * res)
        // Town centres first, so the terrain can be flattened around them.
        repeat(5) { i ->
            val a = MathX.TAU * i / 5f + rng.range(-0.3f, 0.3f)
            val d = if (i == 0) 0f else rng.range(38f, 62f)
            towns += floatArrayOf(kotlin.math.sin(a) * d, kotlin.math.cos(a) * d, rng.range(16f, 24f))
        }
        for (j in 0 until res) for (i in 0 until res) {
            val x = origin + i * cell; val z = origin + j * cell
            val r = sqrt(x * x + z * z) / (SIZE / 2f)
            val broad = noise.fbm(x * 0.012f, z * 0.012f, 4)
            val ridge = noise.ridged(x * 0.02f + 7f, z * 0.02f, 3)
            // Terrain presets change the actual walkable island profile, not just its label.
            var h = when (terrainStyle) {
                "highlands" -> broad * 15f + ridge * 11f + 4f
                "badlands" -> broad * 7f + ridge * 14f + 2.5f
                else -> broad * 10f + ridge * 5f + 3.5f
            }
            // Island falloff into the sea.
            val edge = MathX.smoothstep(0.72f, 0.98f, r)
            h = MathX.lerp(h, -4f, edge)
            // Flatten towns.
            for (t in towns) {
                val dx = x - t[0]; val dz = z - t[1]
                val k = 1f - MathX.smoothstep(t[2] * 0.75f, t[2] * 1.25f, sqrt(dx * dx + dz * dz))
                if (k > 0f) h = MathX.lerp(h, townHeight(noise, t), k)
            }
            heights[j * res + i] = h
        }
        // Dirt roads connect the towns: carve gentle flattened tracks first.
        for (i in towns.indices) {
            val a = towns[i]; val b = towns[(i + 1) % towns.size]
            roads += floatArrayOf(a[0], a[1], b[0], b[1])
            carveRoad(heights, res, origin, cell, a[0], a[1], b[0], b[1])
        }
        terrain = HeightField(origin, origin, cell, res, res, heights)
        physics.heightField = terrain

        val walls = MeshBuilder()
        val roofs = MeshBuilder()
        val props = MeshBuilder()
        for (t in towns) buildTown(t, rng, walls, roofs, props)
        // Watchtowers: real cover with a view — and loot on the platform.
        repeat(if (terrainStyle == "badlands") 4 else 3) {
            val (x, z) = randomLand(rng, 0.72f)
            if (!nearHouse(x, z, 8f)) watchtower(props, x, heightAt(x, z), z, rng)
        }
        // Scattered containers and crates.
        repeat(26) {
            val (x, z) = randomLand(rng, 0.8f)
            if (nearHouse(x, z, 6f)) return@repeat
            val y = heightAt(x, z)
            if (rng.chance(0.55f)) container(props, x, y, z, rng.chance(0.5f), rng)
            else crate(props, x, y, z, rng)
        }
        // Rocks: the badlands are a broken ridge, the island is gentle.
        repeat(if (terrainStyle == "badlands") 70 else if (terrainStyle == "highlands") 52 else 40) {
            val (x, z) = randomLand(rng, 0.9f)
            if (nearHouse(x, z, 5f)) return@repeat
            val y = heightAt(x, z)
            val s = rng.range(0.8f, 2.4f) * (if (terrainStyle == "badlands") 1.25f else 1f)
            val rk = Rock(x, y, z, s * rng.range(1f, 1.8f), s * rng.range(0.6f, 1.1f), s * rng.range(1f, 1.6f), rng.range(0f, MathX.TAU))
            rocks += rk
            physics.addBox(x - rk.sx * 0.42f, y - 0.5f, z - rk.sz * 0.42f, x + rk.sx * 0.42f, y + rk.sy * 0.8f, z + rk.sz * 0.42f, Layers.WORLD, "rock")
        }
        // Trees: dense pines in the highlands, broadleaf on the island, sparse dry scrub in the badlands.
        repeat(if (terrainStyle == "badlands") 55 else if (terrainStyle == "highlands") 170 else 140) {
            val (x, z) = randomLand(rng, 0.88f)
            if (nearHouse(x, z, 4f)) return@repeat
            val y = heightAt(x, z)
            if (y < 0.6f) return@repeat
            val s = rng.range(0.8f, 1.35f) * (if (terrainStyle == "highlands") 0.92f else 1f)
            val tint = when (terrainStyle) {
                "badlands" -> Colors.hsv(rng.range(40f, 70f), rng.range(0.35f, 0.55f), rng.range(0.4f, 0.6f))
                "highlands" -> Colors.hsv(rng.range(100f, 150f), rng.range(0.5f, 0.75f), rng.range(0.32f, 0.5f))
                else -> Colors.hsv(rng.range(85f, 135f), rng.range(0.45f, 0.7f), rng.range(0.45f, 0.7f))
            }
            trees += Tree(x, z, y, s, tint)
            physics.addCylinder(x, z, 0.35f * s, y - 0.5f, y + 5f * s, Layers.WORLD, "tree")
        }
        // Loot in the open too.
        repeat(30) { val (x, z) = randomLand(rng, 0.85f); lootSpots += floatArrayOf(x, heightAt(x, z) + 0.3f, z) }
        // Invisible walls at the shoreline.
        val lim = SIZE * 0.47f
        physics.addBox(-lim - 2f, -10f, -lim - 2f, lim + 2f, 60f, -lim, Layers.WORLD, "edge")
        physics.addBox(-lim - 2f, -10f, lim, lim + 2f, 60f, lim + 2f, Layers.WORLD, "edge")
        physics.addBox(-lim - 2f, -10f, -lim, -lim, 60f, lim, Layers.WORLD, "edge")
        physics.addBox(lim, -10f, -lim, lim + 2f, 60f, lim, Layers.WORLD, "edge")
        buildingMesh = walls.build()
        roofMesh = roofs.build()
        propMesh = props.build()
        terrainMesh = buildTerrainMesh()
    }

    private fun townHeight(noise: Noise, t: FloatArray): Float {
        val broad = noise.fbm(t[0] * 0.012f, t[1] * 0.012f, 4)
        val ridge = noise.ridged(t[0] * 0.02f + 7f, t[1] * 0.02f, 3)
        val base = when (terrainStyle) {
            "highlands" -> broad * 15f + ridge * 11f + 4f
            "badlands" -> broad * 7f + ridge * 14f + 2.5f
            else -> broad * 10f + ridge * 5f + 3.5f
        }
        return max(1.6f, base)
    }

    /** Flatten a walking track between two points: gentler than raw terrain and walkable. */
    private fun carveRoad(heights: FloatArray, res: Int, origin: Float, cell: Float, x0: Float, z0: Float, x1: Float, z1: Float) {
        val len = MathX.length(x1 - x0, z1 - z0)
        if (len < 1f) return
        val steps = (len / 1.6f).toInt().coerceAtLeast(2)
        val half = 4.2f
        for (step in 0..steps) {
            val t = step / steps.toFloat()
            val cx = MathX.lerp(x0, x1, t); val cz = MathX.lerp(z0, z1, t)
            // Target height: average of the terrain in a small window, so the track stays gentle.
            var sum = 0f; var n = 0
            val i0 = ((cx - 2f - origin) / cell).toInt().coerceIn(0, res - 1)
            val i1 = ((cx + 2f - origin) / cell).toInt().coerceIn(0, res - 1)
            val j0 = ((cz - 2f - origin) / cell).toInt().coerceIn(0, res - 1)
            val j1 = ((cz + 2f - origin) / cell).toInt().coerceIn(0, res - 1)
            for (j in j0..j1) for (i in i0..i1) { sum += heights[j * res + i]; n++ }
            val target = if (n > 0) sum / n else 0f
            val ci = ((cx - origin) / cell).toInt()
            val cj = ((cz - origin) / cell).toInt()
            val rad = (half / cell).toInt().coerceAtLeast(1)
            for (j in (cj - rad)..(cj + rad)) for (i in (ci - rad)..(ci + rad)) {
                if (i < 0 || j < 0 || i >= res || j >= res) continue
                val x = origin + i * cell; val z = origin + j * cell
                val d = MathX.length(x - cx, z - cz)
                if (d > half) continue
                val k = (1f - MathX.smoothstep(half * 0.35f, half, d)) * 0.55f
                heights[j * res + i] = MathX.lerp(heights[j * res + i], target, k)
            }
        }
    }

    /** Distance from (x, z) to the nearest road centreline. */
    private fun roadDist(x: Float, z: Float): Float {
        var best = Float.MAX_VALUE
        for (r in roads) {
            val dx = r[2] - r[0]; val dz = r[3] - r[1]
            val l2 = dx * dx + dz * dz
            val t = if (l2 > 1e-3f) MathX.clamp01(((x - r[0]) * dx + (z - r[1]) * dz) / l2) else 0f
            val d = MathX.length(x - (r[0] + dx * t), z - (r[1] + dz * t))
            if (d < best) best = d
        }
        return best
    }

    /** A wooden watchtower with stairs, railings and loot on the deck. */
    private fun watchtower(mb: MeshBuilder, x: Float, y: Float, z: Float, rng: Rng) {
        val h = rng.range(4.6f, 5.8f)
        mb.color = 0xFF6E5B44.toInt()
        for (dx in floatArrayOf(-1.1f, 1.1f)) for (dz in floatArrayOf(-1.1f, 1.1f)) {
            mb.boxMinMax(x + dx - 0.12f, y - 0.3f, z + dz - 0.12f, x + dx + 0.12f, y + h, z + dz + 0.12f)
            physics.addBox(x + dx - 0.12f, y - 0.3f, z + dz - 0.12f, x + dx + 0.12f, y + h, z + dz + 0.12f, Layers.WORLD, "tower")
        }
        mb.boxMinMax(x - 1.5f, y + h, z - 1.5f, x + 1.5f, y + h + 0.22f, z + 1.5f)
        physics.addBox(x - 1.5f, y + h, z - 1.5f, x + 1.5f, y + h + 0.22f, z + 1.5f, Layers.WORLD, "tower")
        mb.color = 0xFF5A4A38.toInt()
        for (side in 0..3) {
            val rx = if (side == 0) x - 1.45f else if (side == 1) x + 1.45f else x
            val rz = if (side == 2) z - 1.45f else if (side == 3) z + 1.45f else z
            val sx = if (side < 2) 0.1f else 2.9f
            val sz = if (side < 2) 2.9f else 0.1f
            mb.boxMinMax(rx - sx / 2f, y + h + 0.22f, rz - sz / 2f, rx + sx / 2f, y + h + 1.1f, rz + sz / 2f)
            physics.addBox(rx - sx / 2f, y + h + 0.22f, rz - sz / 2f, rx + sx / 2f, y + h + 1.1f, rz + sz / 2f, Layers.WORLD, "tower")
        }
        // Straight stair of solid treads up the south face (taller than the
        // controller's 0.42 m step? never: 14 treads keep every step climbable).
        val steps = 14
        mb.color = 0xFF7C674C.toInt()
        for (k in 0 until steps) {
            val sy = y + h * (k + 1) / steps
            val sz2 = z - 1.65f - (steps - 1 - k) * 0.52f
            mb.boxMinMax(x - 0.8f, sy - 0.11f, sz2, x + 0.8f, sy + 0.11f, sz2 + 0.58f)
            physics.addBox(x - 0.8f, sy - 0.11f, sz2, x + 0.8f, sy + 0.11f, sz2 + 0.58f, Layers.WORLD, "tower")
        }
        lootSpots += floatArrayOf(x, y + h + 0.5f, z)
    }

    private fun randomLand(rng: Rng, radiusFraction: Float): Pair<Float, Float> {
        repeat(40) {
            val a = rng.range(0f, MathX.TAU)
            val d = sqrt(rng.nextFloat()) * SIZE / 2f * radiusFraction
            val x = kotlin.math.sin(a) * d; val z = kotlin.math.cos(a) * d
            if (heightAt(x, z) > 0.8f) return x to z
        }
        return 0f to 0f
    }

    private fun nearHouse(x: Float, z: Float, margin: Float) = houses.any { x > it.x0 - margin && x < it.x1 + margin && z > it.z0 - margin && z < it.z1 + margin }

    private fun buildTown(t: FloatArray, rng: Rng, walls: MeshBuilder, roofs: MeshBuilder, props: MeshBuilder) {
        val count = rng.range(4, 7)
        var attempts = 0
        while (houses.count { inTown(it, t) } < count && attempts++ < 60) {
            val w = rng.range(7f, 11f); val d = rng.range(6.5f, 9.5f)
            val cx = t[0] + rng.range(-t[2], t[2]) * 0.8f
            val cz = t[1] + rng.range(-t[2], t[2]) * 0.8f
            val x0 = cx - w / 2f; val z0 = cz - d / 2f; val x1 = cx + w / 2f; val z1 = cz + d / 2f
            if (houses.any { x0 < it.x1 + 4f && x1 > it.x0 - 4f && z0 < it.z1 + 4f && z1 > it.z0 - 4f }) continue
            val floor = max(max(heightAt(x0, z0), heightAt(x1, z1)), max(heightAt(x0, z1), heightAt(x1, z0)))
            val house = House(x0, z0, x1, z1, floor, rng.nextInt(4))
            houses += house
            buildHouse(house, rng, walls, roofs, props)
        }
        // Cover between houses.
        repeat(rng.range(3, 6)) {
            val x = t[0] + rng.range(-t[2], t[2]); val z = t[1] + rng.range(-t[2], t[2])
            if (nearHouse(x, z, 2.5f)) return@repeat
            val y = heightAt(x, z)
            if (rng.chance(0.4f)) container(props, x, y, z, rng.chance(0.5f), rng) else crate(props, x, y, z, rng)
        }
    }

    private fun inTown(h: House, t: FloatArray): Boolean {
        val cx = (h.x0 + h.x1) / 2f - t[0]; val cz = (h.z0 + h.z1) / 2f - t[1]
        return cx * cx + cz * cz < t[2] * t[2] * 1.6f
    }

    private fun buildHouse(h: House, rng: Rng, walls: MeshBuilder, roofs: MeshBuilder, props: MeshBuilder) {
        val th = 0.25f; val height = 3.2f
        val y0 = h.floor - 2f; val y1 = h.floor + height
        walls.color = Colors.hsv(rng.range(20f, 50f), rng.range(0.05f, 0.25f), rng.range(0.78f, 0.95f))
        // Foundation slab (also the floor).
        walls.boxMinMax(h.x0 - 0.15f, y0, h.z0 - 0.15f, h.x1 + 0.15f, h.floor + 0.05f, h.z1 + 0.15f)
        physics.addBox(h.x0 - 0.15f, y0, h.z0 - 0.15f, h.x1 + 0.15f, h.floor + 0.05f, h.z1 + 0.15f, Layers.WORLD, "floor")
        val door = 1.5f
        // Walls: 0 = south (z0), 1 = north (z1), 2 = west (x0), 3 = east (x1). The door side has a gap.
        fun wallX(z0: Float, z1: Float, xa: Float, xb: Float, withDoor: Boolean, withWindow: Boolean) {
            // Wall along X (from xa to xb), thickness along Z.
            if (withDoor) {
                val mid = (xa + xb) / 2f
                segment(walls, xa, h.floor, z0, mid - door / 2f, y1, z1)
                segment(walls, mid + door / 2f, h.floor, z0, xb, y1, z1)
                segment(walls, mid - door / 2f, h.floor + 2.3f, z0, mid + door / 2f, y1, z1)
            } else if (withWindow) {
                val mid = (xa + xb) / 2f
                segment(walls, xa, h.floor, z0, mid - 0.8f, y1, z1)
                segment(walls, mid + 0.8f, h.floor, z0, xb, y1, z1)
                segment(walls, mid - 0.8f, h.floor, z0, mid + 0.8f, h.floor + 1.0f, z1)
                segment(walls, mid - 0.8f, h.floor + 2.1f, z0, mid + 0.8f, y1, z1)
            } else segment(walls, xa, h.floor, z0, xb, y1, z1)
        }
        fun wallZ(x0: Float, x1: Float, za: Float, zb: Float, withDoor: Boolean, withWindow: Boolean) {
            if (withDoor) {
                val mid = (za + zb) / 2f
                segment(walls, x0, h.floor, za, x1, y1, mid - door / 2f)
                segment(walls, x0, h.floor, mid + door / 2f, x1, y1, zb)
                segment(walls, x0, h.floor + 2.3f, mid - door / 2f, x1, y1, mid + door / 2f)
            } else if (withWindow) {
                val mid = (za + zb) / 2f
                segment(walls, x0, h.floor, za, x1, y1, mid - 0.8f)
                segment(walls, x0, h.floor, mid + 0.8f, x1, y1, zb)
                segment(walls, x0, h.floor, mid - 0.8f, x1, h.floor + 1.0f, mid + 0.8f)
                segment(walls, x0, h.floor + 2.1f, mid - 0.8f, x1, y1, mid + 0.8f)
            } else segment(walls, x0, h.floor, za, x1, y1, zb)
        }
        wallX(h.z0, h.z0 + th, h.x0, h.x1, h.doorSide == 0, h.doorSide != 0)
        wallX(h.z1 - th, h.z1, h.x0, h.x1, h.doorSide == 1, h.doorSide != 1)
        wallZ(h.x0, h.x0 + th, h.z0 + th, h.z1 - th, h.doorSide == 2, h.doorSide != 2)
        wallZ(h.x1 - th, h.x1, h.z0 + th, h.z1 - th, h.doorSide == 3, h.doorSide != 3)
        roofs.color = Colors.hsv(rng.range(0f, 30f), rng.range(0.3f, 0.6f), rng.range(0.35f, 0.55f))
        roofs.boxMinMax(h.x0 - 0.4f, y1, h.z0 - 0.4f, h.x1 + 0.4f, y1 + 0.3f, h.z1 + 0.4f)
        physics.addBox(h.x0 - 0.4f, y1, h.z0 - 0.4f, h.x1 + 0.4f, y1 + 0.3f, h.z1 + 0.4f, Layers.WORLD, "roof")
        // Furniture-ish cover and loot inside.
        val cx = (h.x0 + h.x1) / 2f; val cz = (h.z0 + h.z1) / 2f
        crate(props, MathX.lerp(h.x0, h.x1, rng.range(0.2f, 0.35f)), h.floor + 0.05f, MathX.lerp(h.z0, h.z1, rng.range(0.65f, 0.8f)), rng)
        lootSpots += floatArrayOf(cx, h.floor + 0.35f, cz)
        lootSpots += floatArrayOf(MathX.lerp(h.x0, h.x1, 0.8f), h.floor + 0.35f, MathX.lerp(h.z0, h.z1, 0.25f))
    }

    private fun segment(mb: MeshBuilder, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float) {
        if (x1 - x0 < 0.05f || z1 - z0 < 0.05f || y1 - y0 < 0.05f) return
        mb.boxMinMax(x0, y0, z0, x1, y1, z1)
        physics.addBox(x0, y0, z0, x1, y1, z1, Layers.WORLD, "wall")
    }

    private fun container(mb: MeshBuilder, x: Float, y: Float, z: Float, alongX: Boolean, rng: Rng) {
        val l = 6f; val w = 2.4f; val h = 2.6f
        val sx = if (alongX) l else w; val sz = if (alongX) w else l
        mb.color = CONTAINER_COLORS[rng.nextInt(CONTAINER_COLORS.size)]
        mb.boxMinMax(x - sx / 2f, y - 0.4f, z - sz / 2f, x + sx / 2f, y + h, z + sz / 2f)
        physics.addBox(x - sx / 2f, y - 0.4f, z - sz / 2f, x + sx / 2f, y + h, z + sz / 2f, Layers.WORLD, "container")
        lootSpots += floatArrayOf(x + (if (alongX) 0f else sx), y + 0.3f, z + (if (alongX) sz else 0f))
    }

    private fun crate(mb: MeshBuilder, x: Float, y: Float, z: Float, rng: Rng) {
        val s = 1.0f
        mb.color = Colors.hsv(rng.range(25f, 38f), 0.55f, rng.range(0.55f, 0.7f))
        mb.boxMinMax(x - s / 2f, y, z - s / 2f, x + s / 2f, y + s, z + s / 2f)
        physics.addBox(x - s / 2f, y, z - s / 2f, x + s / 2f, y + s, z + s / 2f, Layers.PROPS, "crate")
    }

    private fun buildTerrainMesh(): Mesh {
        val mb = MeshBuilder()
        val res = RES
        val base = mb.vertexCount
        val n = com.oea.engine.math.Vec3()
        for (j in 0 until res) for (i in 0 until res) {
            val x = terrain.originX + i * terrain.cellSize; val z = terrain.originZ + j * terrain.cellSize
            val h = terrain.heights[j * res + i]
            terrain.normalAt(x, z, n)
            val slope = 1f - n.y
            val sand = when (terrainStyle) {
                "badlands" -> 0xFFD9B98A.toInt()
                "highlands" -> 0xFFCFC7A8.toInt()
                else -> 0xFFD9C79A.toInt()
            }
            val sandDeep = when (terrainStyle) {
                "badlands" -> 0xFFC09468.toInt()
                "highlands" -> 0xFFB8AE8C.toInt()
                else -> 0xFFC9B07A.toInt()
            }
            val rockLo = when (terrainStyle) {
                "badlands" -> 0xFF8A4B36.toInt()
                "highlands" -> 0xFF6F7560.toInt()
                else -> 0xFF6F7A5A.toInt()
            }
            val rockHi = when (terrainStyle) {
                "badlands" -> 0xFFB06A48.toInt()
                "highlands" -> 0xFF8A8478.toInt()
                else -> 0xFF8A8478.toInt()
            }
            val grassLo = when (terrainStyle) {
                "badlands" -> 0xFFA88A52.toInt()
                "highlands" -> 0xFF4E7A34.toInt()
                else -> 0xFF5E8F3E.toInt()
            }
            val grassHi = when (terrainStyle) {
                "badlands" -> 0xFFC2A068.toInt()
                "highlands" -> 0xFF6B9A46.toInt()
                else -> 0xFF7BA34A.toInt()
            }
            var c = when {
                h < 0.6f -> Colors.lerp(sand, sandDeep, MathX.clamp01(-h / 3f))
                slope > 0.25f -> Colors.lerp(rockLo, rockHi, MathX.clamp01((slope - 0.25f) * 4f))
                else -> Colors.lerp(grassLo, grassHi, MathX.clamp01(h / 12f))
            }
            // Alpine snowcaps and badland ridge caps: the levels read differently at a glance.
            if (terrainStyle == "highlands" && h > 16f) {
                c = Colors.lerp(c, 0xFFF2F5F8.toInt(), MathX.smoothstep(16f, 24f, h) * (1f - slope * 0.8f))
            } else if (terrainStyle == "badlands" && slope > 0.42f) {
                c = Colors.lerp(c, 0xFFD8C4A2.toInt(), MathX.clamp01((slope - 0.42f) * 2.2f))
            }
            // Dirt roads between towns.
            val rd = roadDist(x, z)
            if (rd < 3.2f) {
                val dirt = if (terrainStyle == "badlands") 0xFF9A6B48.toInt() else 0xFF8A6F4E.toInt()
                c = Colors.lerp(c, dirt, (1f - MathX.smoothstep(1.4f, 3.2f, rd)) * 0.85f)
            }
            mb.color = c
            mb.vertex(x, h, z, n.x, n.y, n.z, i * 0.25f, j * 0.25f)
        }
        for (j in 0 until res - 1) for (i in 0 until res - 1) {
            val a = base + j * res + i
            mb.quad(a, a + res, a + res + 1, a + 1)
        }
        return mb.build()
    }

    companion object {
        const val SIZE = 230f
        const val RES = 116
        val CONTAINER_COLORS = intArrayOf(0xFFB23A2F.toInt(), 0xFF2F6FB2.toInt(), 0xFF2F9A5E.toInt(), 0xFFD9A23A.toInt(), 0xFF7A7F87.toInt())

        val treeTrunk: Mesh by lazy { MeshBuilder().cylinder(0f, 0f, 0f, 0.28f, 2.6f, 8).build() }
        /** Alpine pine: stacked cones. */
        val treeCrown: Mesh by lazy {
            MeshBuilder().cone(0f, 1.8f, 0f, 2.0f, 2.6f, 10).cone(0f, 3.1f, 0f, 1.55f, 2.2f, 10).cone(0f, 4.2f, 0f, 1.0f, 1.8f, 10).build()
        }
        /** Island broadleaf: wide layered canopy. */
        val treeBroad: Mesh by lazy {
            MeshBuilder().sphere(0f, 2.4f, 0f, 1.9f, 10, 7)
                .sphere(0f, 3.6f, 0f, 1.45f, 9, 6)
                .sphere(0.9f, 3.0f, 0.4f, 1.1f, 8, 5)
                .sphere(-0.9f, 3.1f, -0.3f, 1.05f, 8, 5)
                .build()
        }
        val rockMesh: Mesh by lazy { MeshBuilder().sphere(0f, 0f, 0f, 0.5f, 7, 5).build() }
        @Suppress("unused") private fun unused() = min(0, 0)
    }
}
