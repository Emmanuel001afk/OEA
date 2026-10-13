package com.oea.engine.physics

import com.oea.engine.math.Aabb
import com.oea.engine.math.Intersect
import com.oea.engine.math.MathX
import com.oea.engine.math.Vec3
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Collision layers (bit flags). Games may define more. */
object Layers {
    const val WORLD = 1
    const val PROPS = 2
    const val TRIGGER = 4
    const val ALL = -1
}

/** A static collision shape. */
sealed class Collider(val layer: Int, val userData: Any?) {
    internal var stamp = 0
    val bounds = Aabb()

    class Box(minX: Float, minY: Float, minZ: Float, maxX: Float, maxY: Float, maxZ: Float, layer: Int, userData: Any?) : Collider(layer, userData) {
        init { bounds.set(minX, minY, minZ, maxX, maxY, maxZ) }
    }

    /** Vertical cylinder (trees, pillars, barrels). */
    class Cylinder(val cx: Float, val cz: Float, val radius: Float, val y0: Float, val y1: Float, layer: Int, userData: Any?) : Collider(layer, userData) {
        init { bounds.set(cx - radius, y0, cz - radius, cx + radius, y1, cz + radius) }
    }

    class Sphere(val cx: Float, val cy: Float, val cz: Float, val radius: Float, layer: Int, userData: Any?) : Collider(layer, userData) {
        init { bounds.set(cx - radius, cy - radius, cz - radius, cx + radius, cy + radius, cz + radius) }
    }
}

/** Result of a ray query. */
class RayHit {
    var distance = 0f
    val point = Vec3()
    val normal = Vec3()
    var collider: Collider? = null
    /** True when the terrain height field was hit. */
    var terrain = false
    val userData: Any? get() = collider?.userData
}

/** Regular grid of heights over XZ with bilinear sampling (terrain). */
class HeightField(val originX: Float, val originZ: Float, val cellSize: Float, val resX: Int, val resZ: Int, val heights: FloatArray) {
    init { require(heights.size == resX * resZ) { "heights must have resX*resZ values" } }

    val sizeX get() = (resX - 1) * cellSize
    val sizeZ get() = (resZ - 1) * cellSize

    fun heightAt(x: Float, z: Float): Float {
        val fx = MathX.clamp((x - originX) / cellSize, 0f, (resX - 1).toFloat() - 1e-4f)
        val fz = MathX.clamp((z - originZ) / cellSize, 0f, (resZ - 1).toFloat() - 1e-4f)
        val ix = fx.toInt(); val iz = fz.toInt()
        val tx = fx - ix; val tz = fz - iz
        val h00 = heights[iz * resX + ix]; val h10 = heights[iz * resX + ix + 1]
        val h01 = heights[(iz + 1) * resX + ix]; val h11 = heights[(iz + 1) * resX + ix + 1]
        return MathX.lerp(MathX.lerp(h00, h10, tx), MathX.lerp(h01, h11, tx), tz)
    }

    fun normalAt(x: Float, z: Float, out: Vec3): Vec3 {
        val e = cellSize * 0.5f
        val hl = heightAt(x - e, z); val hr = heightAt(x + e, z)
        val hd = heightAt(x, z - e); val hu = heightAt(x, z + e)
        return out.set(hl - hr, 2f * e, hd - hu).nor()
    }

    fun contains(x: Float, z: Float) = x >= originX && z >= originZ && x <= originX + sizeX && z <= originZ + sizeZ

    /** Ray march with bisection refinement. */
    fun raycast(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, maxDist: Float): Float {
        val step = cellSize * 0.5f
        var t = 0f
        var prevT = 0f
        var prevAbove = oy - heightAt(ox, oz) > 0f
        if (!prevAbove) return 0f
        while (t < maxDist) {
            t = min(maxDist, t + step)
            val x = ox + dx * t; val y = oy + dy * t; val z = oz + dz * t
            val above = y - heightAt(x, z) > 0f
            if (!above) {
                var lo = prevT; var hi = t
                repeat(10) {
                    val mid = (lo + hi) / 2f
                    if (oy + dy * mid - heightAt(ox + dx * mid, oz + dz * mid) > 0f) lo = mid else hi = mid
                }
                return hi
            }
            prevAbove = above
            prevT = t
            if (t >= maxDist) break
        }
        return -1f
    }
}

/**
 * Static collision world: boxes, cylinders, spheres and an optional height
 * field, indexed by an XZ spatial hash for fast ray casts and overlap tests.
 */
class PhysicsWorld(private val cellSize: Float = 8f) {
    private val colliders = ArrayList<Collider>()
    private val grid = HashMap<Long, ArrayList<Collider>>()
    private var stamp = 0
    var heightField: HeightField? = null

    val count get() = colliders.size
    fun all(): List<Collider> = colliders

    fun addBox(minX: Float, minY: Float, minZ: Float, maxX: Float, maxY: Float, maxZ: Float, layer: Int = Layers.WORLD, userData: Any? = null): Collider =
        insert(Collider.Box(minX, minY, minZ, maxX, maxY, maxZ, layer, userData))

    fun addBox(box: Aabb, layer: Int = Layers.WORLD, userData: Any? = null): Collider =
        addBox(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, layer, userData)

    fun addCylinder(cx: Float, cz: Float, radius: Float, y0: Float, y1: Float, layer: Int = Layers.WORLD, userData: Any? = null): Collider =
        insert(Collider.Cylinder(cx, cz, radius, y0, y1, layer, userData))

    fun addSphere(cx: Float, cy: Float, cz: Float, radius: Float, layer: Int = Layers.WORLD, userData: Any? = null): Collider =
        insert(Collider.Sphere(cx, cy, cz, radius, layer, userData))

    fun remove(c: Collider) {
        colliders.remove(c)
        forCells(c.bounds) { key -> grid[key]?.remove(c) }
    }

    fun clear() { colliders.clear(); grid.clear() }

    private fun insert(c: Collider): Collider {
        colliders += c
        forCells(c.bounds) { key -> grid.getOrPut(key) { ArrayList(4) } += c }
        return c
    }

    private inline fun forCells(b: Aabb, block: (Long) -> Unit) {
        val x0 = floor(b.minX / cellSize).toInt(); val x1 = floor(b.maxX / cellSize).toInt()
        val z0 = floor(b.minZ / cellSize).toInt(); val z1 = floor(b.maxZ / cellSize).toInt()
        for (x in x0..x1) for (z in z0..z1) block(key(x, z))
    }

    private fun key(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)

    /** Calls [block] once for each collider whose cell range overlaps [b]. */
    fun query(b: Aabb, layerMask: Int = Layers.ALL, block: (Collider) -> Unit) {
        stamp++
        forCells(b) { k ->
            val list = grid[k] ?: return@forCells
            for (c in list) {
                if (c.stamp == stamp || c.layer and layerMask == 0) continue
                c.stamp = stamp
                if (c.bounds.overlaps(b)) block(c)
            }
        }
    }

    /** Casts a ray against colliders (and terrain); fills [hit] with the closest result. */
    fun raycast(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, maxDist: Float, hit: RayHit, layerMask: Int = Layers.ALL, ignore: Any? = null): Boolean {
        var best = maxDist
        var bestCollider: Collider? = null
        stamp++
        // 2D DDA over the XZ grid.
        var cx = floor(ox / cellSize).toInt(); var cz = floor(oz / cellSize).toInt()
        val stepX = if (dx > 0f) 1 else -1; val stepZ = if (dz > 0f) 1 else -1
        val tDeltaX = if (abs(dx) < 1e-9f) Float.MAX_VALUE else cellSize / abs(dx)
        val tDeltaZ = if (abs(dz) < 1e-9f) Float.MAX_VALUE else cellSize / abs(dz)
        var tMaxX = if (abs(dx) < 1e-9f) Float.MAX_VALUE else ((if (dx > 0f) (cx + 1) * cellSize - ox else ox - cx * cellSize) / abs(dx))
        var tMaxZ = if (abs(dz) < 1e-9f) Float.MAX_VALUE else ((if (dz > 0f) (cz + 1) * cellSize - oz else oz - cz * cellSize) / abs(dz))
        var tCell = 0f
        var guard = 0
        while (tCell <= best && guard++ < 4096) {
            grid[key(cx, cz)]?.let { list ->
                for (c in list) {
                    if (c.stamp == stamp || c.layer and layerMask == 0 || (ignore != null && c.userData === ignore)) continue
                    c.stamp = stamp
                    val t = when (c) {
                        is Collider.Box -> Intersect.rayAabb(ox, oy, oz, dx, dy, dz, c.bounds, best)
                        is Collider.Cylinder -> Intersect.rayCylinderY(ox, oy, oz, dx, dy, dz, c.cx, c.cz, c.radius, c.y0, c.y1)
                        is Collider.Sphere -> Intersect.raySphere(ox, oy, oz, dx, dy, dz, c.cx, c.cy, c.cz, c.radius)
                    }
                    if (t >= 0f && t < best) { best = t; bestCollider = c }
                }
            }
            if (tMaxX < tMaxZ) { tCell = tMaxX; tMaxX += tDeltaX; cx += stepX } else { tCell = tMaxZ; tMaxZ += tDeltaZ; cz += stepZ }
            if (tDeltaX == Float.MAX_VALUE && tDeltaZ == Float.MAX_VALUE) break
        }
        var terrain = false
        heightField?.let { hf ->
            val t = hf.raycast(ox, oy, oz, dx, dy, dz, best)
            if (t >= 0f && t < best) { best = t; terrain = true; bestCollider = null }
        }
        if (bestCollider == null && !terrain) return false
        hit.distance = best
        hit.point.set(ox + dx * best, oy + dy * best, oz + dz * best)
        hit.collider = bestCollider
        hit.terrain = terrain
        val c = bestCollider
        when {
            terrain -> heightField!!.normalAt(hit.point.x, hit.point.z, hit.normal)
            c is Collider.Box -> Intersect.aabbNormal(c.bounds, hit.point.x, hit.point.y, hit.point.z, hit.normal)
            c is Collider.Cylinder -> {
                if (hit.point.y >= c.y1 - 1e-3f) hit.normal.set(0f, 1f, 0f)
                else if (hit.point.y <= c.y0 + 1e-3f) hit.normal.set(0f, -1f, 0f)
                else hit.normal.set(hit.point.x - c.cx, 0f, hit.point.z - c.cz).nor()
            }
            c is Collider.Sphere -> hit.normal.set(hit.point.x - c.cx, hit.point.y - c.cy, hit.point.z - c.cz).nor()
        }
        return true
    }

    /** True if nothing blocks the segment between two points. */
    fun lineOfSight(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, layerMask: Int = Layers.WORLD, hit: RayHit = RayHit()): Boolean {
        val dx = bx - ax; val dy = by - ay; val dz = bz - az
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        if (d < 1e-4f) return true
        return !raycast(ax, ay, az, dx / d, dy / d, dz / d, d - 0.01f, hit, layerMask)
    }

    /** Highest walkable surface at (x, z) at or below [fromY] (terrain or box tops), for a disc of [radius]. */
    fun groundHeight(x: Float, z: Float, fromY: Float, radius: Float = 0f): Float {
        var g = heightField?.heightAt(x, z) ?: -1000f
        queryBox.set(x - radius, -10000f, z - radius, x + radius, fromY + 0.01f, z + radius)
        query(queryBox, Layers.WORLD or Layers.PROPS) { c ->
            val top = when (c) {
                is Collider.Box -> if (circleRect(x, z, radius * 0.7f, c.bounds)) c.bounds.maxY else Float.NEGATIVE_INFINITY
                is Collider.Cylinder -> if (MathX.length(x - c.cx, z - c.cz) <= c.radius + radius * 0.7f) c.y1 else Float.NEGATIVE_INFINITY
                is Collider.Sphere -> Float.NEGATIVE_INFINITY
            }
            if (top <= fromY + 0.01f && top > g) g = top
        }
        return g
    }

    private val queryBox = Aabb()

    companion object {
        fun circleRect(x: Float, z: Float, r: Float, b: Aabb): Boolean {
            val cx = MathX.clamp(x, b.minX, b.maxX); val cz = MathX.clamp(z, b.minZ, b.maxZ)
            val dx = x - cx; val dz = z - cz
            return dx * dx + dz * dz <= r * r
        }
    }
}

/**
 * Upright capsule character controller: collide-and-slide against walls,
 * steps up small ledges, stands on boxes and terrain, bumps its head on
 * ceilings. Position is the feet.
 */
class CharacterController(var radius: Float = 0.4f, var height: Float = 1.8f, var stepHeight: Float = 0.42f) {
    val position = Vec3()
    val velocity = Vec3()
    var grounded = false; private set
    var gravity = -24f
    private val box = Aabb()
    /** True if a wall was touched during the last move. */
    var hitWall = false; private set

    /**
     * Moves with horizontal velocity ([vx], [vz]) for [dt]; [jumpSpeed] > 0
     * jumps when grounded.
     */
    fun move(world: PhysicsWorld, vx: Float, vz: Float, jumpSpeed: Float, dt: Float) {
        velocity.x = vx; velocity.z = vz
        if (grounded && jumpSpeed > 0f) { velocity.y = jumpSpeed; grounded = false }
        velocity.y += gravity * dt
        hitWall = false
        // Horizontal, in sub-steps so fast movement never tunnels through thin walls.
        val dist = sqrt(vx * vx + vz * vz) * dt
        val steps = max(1, (dist / (radius * 0.5f)).toInt() + 1)
        val sx = vx * dt / steps; val sz = vz * dt / steps
        for (i in 0 until steps) {
            position.x += sx; position.z += sz
            resolveWalls(world)
        }
        // Vertical.
        position.y += velocity.y * dt
        val ground = world.groundHeight(position.x, position.z, position.y + stepHeight, radius)
        if (position.y <= ground + 1e-3f && velocity.y <= 0.01f) {
            // Snap down slopes and up steps.
            position.y = ground
            velocity.y = 0f
            grounded = true
        } else if (grounded && velocity.y <= 0f && position.y - ground < stepHeight) {
            position.y = ground
            velocity.y = 0f
        } else {
            grounded = false
        }
        if (velocity.y > 0f) {
            val head = position.y + height
            box.set(position.x - radius, position.y + stepHeight, position.z - radius, position.x + radius, head + 0.05f, position.z + radius)
            world.query(box, Layers.WORLD) { c ->
                if (c is Collider.Box && c.bounds.minY > position.y + stepHeight && PhysicsWorld.circleRect(position.x, position.z, radius * 0.9f, c.bounds)) {
                    position.y = min(position.y, c.bounds.minY - height - 0.01f)
                    velocity.y = 0f
                }
            }
        }
    }

    private fun resolveWalls(world: PhysicsWorld) {
        repeat(3) {
            var moved = false
            box.set(position.x - radius, position.y + stepHeight, position.z - radius, position.x + radius, position.y + height, position.z + radius)
            world.query(box, Layers.WORLD or Layers.PROPS) { c ->
                when (c) {
                    is Collider.Box -> {
                        if (c.bounds.maxY <= position.y + stepHeight || c.bounds.minY >= position.y + height) return@query
                        val b = c.bounds
                        val cx = MathX.clamp(position.x, b.minX, b.maxX)
                        val cz = MathX.clamp(position.z, b.minZ, b.maxZ)
                        var dx = position.x - cx; var dz = position.z - cz
                        val d2 = dx * dx + dz * dz
                        if (d2 > radius * radius) return@query
                        if (d2 < 1e-8f) {
                            // Centre inside the box: push out along the shallowest axis.
                            val left = position.x - b.minX; val right = b.maxX - position.x
                            val back = position.z - b.minZ; val front = b.maxZ - position.z
                            val m = minOf(minOf(left, right), minOf(back, front))
                            when (m) {
                                left -> position.x = b.minX - radius
                                right -> position.x = b.maxX + radius
                                back -> position.z = b.minZ - radius
                                else -> position.z = b.maxZ + radius
                            }
                        } else {
                            val d = sqrt(d2)
                            dx /= d; dz /= d
                            position.x = cx + dx * radius
                            position.z = cz + dz * radius
                        }
                        moved = true
                        hitWall = true
                    }
                    is Collider.Cylinder -> {
                        if (c.y1 <= position.y + stepHeight || c.y0 >= position.y + height) return@query
                        var dx = position.x - c.cx; var dz = position.z - c.cz
                        val d = sqrt(dx * dx + dz * dz)
                        val min = c.radius + radius
                        if (d >= min) return@query
                        if (d < 1e-4f) { dx = 1f; dz = 0f } else { dx /= d; dz /= d }
                        position.x = c.cx + dx * min
                        position.z = c.cz + dz * min
                        moved = true
                        hitWall = true
                    }
                    is Collider.Sphere -> Unit
                }
            }
            if (!moved) return
        }
    }

    fun teleport(x: Float, y: Float, z: Float) { position.set(x, y, z); velocity.setZero(); grounded = false }
}
