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

/** Seeded battle-royale island: terrain, towns with enterable houses, cover, trees and loot spots. */
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
    /** Static geometry merged per material (built on demand on the GL side). */
    lateinit var terrainMesh: Mesh
        private set
    lateinit var buildingMesh: Mesh
        private set
    lateinit var roofMesh: Mesh
        private set
    lateinit var propMesh: Mesh
        private set

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
        terrain = HeightField(origin, origin, cell, res, res, heights)
        physics.heightField = terrain

        val walls = MeshBuilder()
        val roofs = MeshBuilder()
        val props = MeshBuilder()
        for (t in towns) buildTown(t, rng, walls, roofs, props)
        // Scattered containers and crates.
        repeat(26) {
            val (x, z) = randomLand(rng, 0.8f)
            if (nearHouse(x, z, 6f)) return@repeat
            val y = heightAt(x, z)
            if (rng.chance(0.55f)) container(props, x, y, z, rng.chance(0.5f), rng)
            else crate(props, x, y, z, rng)
        }
        // Rocks.
        repeat(40) {
            val (x, z) = randomLand(rng, 0.9f)
            if (nearHouse(x, z, 5f)) return@repeat
            val y = heightAt(x, z)
            val s = rng.range(0.8f, 2.4f)
            val rk = Rock(x, y, z, s * rng.range(1f, 1.8f), s * rng.range(0.6f, 1.1f), s * rng.range(1f, 1.6f), rng.range(0f, MathX.TAU))
            rocks += rk
            physics.addBox(x - rk.sx * 0.42f, y - 0.5f, z - rk.sz * 0.42f, x + rk.sx * 0.42f, y + rk.sy * 0.8f, z + rk.sz * 0.42f, Layers.WORLD, "rock")
        }
        // Trees.
        repeat(140) {
            val (x, z) = randomLand(rng, 0.88f)
            if (nearHouse(x, z, 4f)) return@repeat
            val y = heightAt(x, z)
            if (y < 0.6f) return@repeat
            val s = rng.range(0.8f, 1.35f)
            trees += Tree(x, z, y, s, Colors.hsv(rng.range(85f, 135f), rng.range(0.45f, 0.7f), rng.range(0.45f, 0.7f)))
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
            mb.color = when {
                h < 0.6f -> Colors.lerp(0xFFD9C79A.toInt(), 0xFFC9B07A.toInt(), MathX.clamp01(-h / 3f))   // sand
                slope > 0.25f -> Colors.lerp(0xFF6F7A5A.toInt(), 0xFF8A8478.toInt(), MathX.clamp01((slope - 0.25f) * 4f)) // rock
                else -> Colors.lerp(0xFF5E8F3E.toInt(), 0xFF7BA34A.toInt(), MathX.clamp01(h / 12f))       // grass
            }
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

        val terrainMaterial by lazy { Material(color = Colors.WHITE, texture = Textures.noise(256, 30f, 5, 0xFFBDBDBD.toInt(), 0xFFFFFFFF.toInt()), specular = 0.04f, uvScaleX = 1f, uvScaleY = 1f) }
        val wallMaterial by lazy { Material(color = Colors.WHITE, texture = Textures.noise(128, 12f, 6, 0xFFDDDDDD.toInt(), 0xFFFFFFFF.toInt()), specular = 0.1f) }
        val roofMaterial = Material(color = Colors.WHITE, specular = 0.2f, shininess = 30f)
        val propMaterial = Material(color = Colors.WHITE, specular = 0.35f, shininess = 40f)
        val water = Material(color = 0xB32A86C8.toInt(), emissive = 0xFF0E3A5C.toInt(), emissiveStrength = 0.4f, specular = 1f, shininess = 150f, transparent = true, castShadows = false, receiveShadows = false)
        val trunk = Material(color = 0xFF6B4A2E.toInt(), specular = 0.05f)
        val leaves = Material(color = Colors.WHITE, specular = 0.08f, rim = 0.15f)
        val rock = Material(color = 0xFF8C8A84.toInt(), specular = 0.15f)
        val treeTrunk: Mesh by lazy { MeshBuilder().cylinder(0f, 0f, 0f, 0.28f, 2.6f, 8).build() }
        val treeCrown: Mesh by lazy {
            MeshBuilder().cone(0f, 1.8f, 0f, 2.0f, 2.6f, 10).cone(0f, 3.1f, 0f, 1.55f, 2.2f, 10).cone(0f, 4.2f, 0f, 1.0f, 1.8f, 10).build()
        }
        val rockMesh: Mesh by lazy { MeshBuilder().sphere(0f, 0f, 0f, 0.5f, 7, 5).build() }
        @Suppress("unused") private fun unused() = min(0, 0)
    }
}
