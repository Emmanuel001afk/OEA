package com.oea.engine.ecs

import com.oea.engine.graphics.Material
import com.oea.engine.graphics.Mesh
import com.oea.engine.graphics.Renderer3D
import com.oea.engine.math.Colors
import com.oea.engine.math.Mat4
import com.oea.engine.math.Vec3

/**
 * Entity id: 20 bits of slot index and 11 bits of generation, so a stale id
 * of a destroyed entity never aliases a newly created one.
 */
typealias Entity = Int

/** Sparse-set storage for one component type: O(1) add/remove/get and cache-friendly iteration. */
class ComponentStore<T : Any> internal constructor() {
    private var sparse = IntArray(256) { -1 }
    internal var entities = IntArray(64)
    internal val components = ArrayList<T>(64)
    val size get() = components.size

    internal fun put(e: Entity, c: T) {
        val idx = World.indexOf(e)
        if (idx >= sparse.size) sparse = sparse.copyOf(maxOf(idx + 1, sparse.size * 2)).also { it.fill(-1, sparse.size) }
        val d = sparse[idx]
        if (d >= 0 && entities[d] == e) { components[d] = c; return }
        if (components.size == entities.size) entities = entities.copyOf(entities.size * 2)
        sparse[idx] = components.size
        entities[components.size] = e
        components += c
    }

    operator fun get(e: Entity): T? {
        val idx = World.indexOf(e)
        if (idx >= sparse.size) return null
        val d = sparse[idx]
        return if (d >= 0 && d < components.size && entities[d] == e) components[d] else null
    }

    fun has(e: Entity) = get(e) != null

    internal fun remove(e: Entity): Boolean {
        val idx = World.indexOf(e)
        if (idx >= sparse.size) return false
        val d = sparse[idx]
        if (d < 0 || d >= components.size || entities[d] != e) return false
        val last = components.size - 1
        if (d != last) {
            entities[d] = entities[last]
            components[d] = components[last]
            sparse[World.indexOf(entities[d])] = d
        }
        components.removeAt(last)
        sparse[idx] = -1
        return true
    }

    fun entityAt(i: Int): Entity = entities[i]
    fun componentAt(i: Int): T = components[i]
}

/** A system updated by [World.update] in ascending [priority]. */
abstract class EcsSystem(val priority: Int = 0) {
    open fun onAdded(world: World) {}
    abstract fun update(world: World, dt: Float)
}

/**
 * Entity–component–system world. Components are plain Kotlin objects;
 * systems iterate with [each]. Destruction is deferred to the end of
 * [update] so systems can destroy entities while iterating.
 *
 * ```
 * val e = world.create()
 * world.add(e, Transform(Vec3(0f, 1f, 0f)))
 * world.add(e, Velocity(Vec3(0f, 0f, 5f)))
 * world.each<Transform, Velocity> { _, t, v -> t.position.mulAdd(v.linear, dt) }
 * ```
 */
class World {
    private var generations = IntArray(256)
    private var alive = BooleanArray(256)
    private var free = IntArray(64)
    private var freeCount = 0
    private var nextIndex = 0
    private val stores = HashMap<Class<*>, ComponentStore<*>>()
    private val systems = ArrayList<EcsSystem>()
    private val pendingDestroy = ArrayList<Entity>()
    var entityCount = 0
        private set

    fun create(): Entity {
        val idx = if (freeCount > 0) free[--freeCount] else nextIndex++
        if (idx >= generations.size) {
            generations = generations.copyOf(generations.size * 2)
            alive = alive.copyOf(alive.size * 2)
        }
        alive[idx] = true
        entityCount++
        return compose(idx, generations[idx])
    }

    fun isAlive(e: Entity): Boolean {
        val idx = indexOf(e)
        return idx < alive.size && alive[idx] && generations[idx] == generationOf(e)
    }

    /** Destroys at the end of the current update (or immediately via [flushDestroyed]). */
    fun destroy(e: Entity) { if (isAlive(e)) pendingDestroy += e }

    fun flushDestroyed() {
        if (pendingDestroy.isEmpty()) return
        for (e in pendingDestroy) {
            if (!isAlive(e)) continue
            for (s in stores.values) s.remove(e)
            val idx = indexOf(e)
            alive[idx] = false
            generations[idx] = (generations[idx] + 1) and GEN_MASK
            if (freeCount == free.size) free = free.copyOf(free.size * 2)
            free[freeCount++] = idx
            entityCount--
        }
        pendingDestroy.clear()
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> store(type: Class<T>): ComponentStore<T> = stores.getOrPut(type) { ComponentStore<T>() } as ComponentStore<T>
    inline fun <reified T : Any> store(): ComponentStore<T> = store(T::class.java)

    fun <T : Any> add(e: Entity, component: T): T {
        require(isAlive(e)) { "Entity $e is not alive" }
        store(component.javaClass).put(e, component)
        return component
    }

    fun <T : Any> get(e: Entity, type: Class<T>): T? = store(type)[e]
    inline fun <reified T : Any> get(e: Entity): T? = store(T::class.java)[e]
    inline fun <reified T : Any> has(e: Entity): Boolean = store(T::class.java).has(e)
    fun <T : Any> remove(e: Entity, type: Class<T>) { store(type).remove(e) }

    inline fun <reified A : Any> each(block: (Entity, A) -> Unit) {
        val sa = store<A>()
        var i = 0
        while (i < sa.size) { block(sa.entityAt(i), sa.componentAt(i)); i++ }
    }

    inline fun <reified A : Any, reified B : Any> each(block: (Entity, A, B) -> Unit) {
        val sa = store<A>(); val sb = store<B>()
        if (sa.size <= sb.size) {
            var i = 0
            while (i < sa.size) { val e = sa.entityAt(i); val b = sb[e]; if (b != null) block(e, sa.componentAt(i), b); i++ }
        } else {
            var i = 0
            while (i < sb.size) { val e = sb.entityAt(i); val a = sa[e]; if (a != null) block(e, a, sb.componentAt(i)); i++ }
        }
    }

    inline fun <reified A : Any, reified B : Any, reified C : Any> each(block: (Entity, A, B, C) -> Unit) {
        val sa = store<A>(); val sb = store<B>(); val sc = store<C>()
        var i = 0
        while (i < sa.size) {
            val e = sa.entityAt(i)
            val b = sb[e]; val c = sc[e]
            if (b != null && c != null) block(e, sa.componentAt(i), b, c)
            i++
        }
    }

    fun addSystem(system: EcsSystem): World {
        systems += system
        systems.sortBy { it.priority }
        system.onAdded(this)
        return this
    }

    fun update(dt: Float) {
        for (s in systems) s.update(this, dt)
        flushDestroyed()
    }

    fun clear() {
        stores.clear()
        generations.fill(0); alive.fill(false)
        freeCount = 0; nextIndex = 0; entityCount = 0
        pendingDestroy.clear()
    }

    companion object {
        private const val INDEX_BITS = 20
        private const val INDEX_MASK = (1 shl INDEX_BITS) - 1
        private const val GEN_MASK = 0x7FF
        fun indexOf(e: Entity): Int = e and INDEX_MASK
        fun generationOf(e: Entity): Int = (e ushr INDEX_BITS) and GEN_MASK
        private fun compose(index: Int, gen: Int): Entity = (gen shl INDEX_BITS) or index
    }
}

// ------------------------------------------------------------------ built-in components

class Transform(
    val position: Vec3 = Vec3(),
    var yaw: Float = 0f,
    var pitch: Float = 0f,
    var roll: Float = 0f,
    val scale: Vec3 = Vec3(1f, 1f, 1f),
) {
    fun matrix(out: Mat4): Mat4 = out.setTRS(position.x, position.y, position.z, yaw, pitch, roll, scale.x, scale.y, scale.z)
}

class Velocity(val linear: Vec3 = Vec3(), var yawRate: Float = 0f, var gravity: Float = 0f, var drag: Float = 0f)

class Lifetime(var remaining: Float)

class MeshRenderer(var mesh: Mesh, var material: Material, var tint: Int = Colors.WHITE, var visible: Boolean = true)

class Tag(val name: String)

// ------------------------------------------------------------------ built-in systems

/** Integrates [Velocity] into [Transform]. */
class MovementSystem(priority: Int = 0) : EcsSystem(priority) {
    override fun update(world: World, dt: Float) {
        world.each<Transform, Velocity> { _, t, v ->
            if (v.gravity != 0f) v.linear.y += v.gravity * dt
            if (v.drag > 0f) v.linear.scl(1f / (1f + v.drag * dt))
            t.position.mulAdd(v.linear, dt)
            t.yaw += v.yawRate * dt
        }
    }
}

/** Destroys entities whose [Lifetime] ran out. */
class LifetimeSystem(priority: Int = 100) : EcsSystem(priority) {
    override fun update(world: World, dt: Float) {
        world.each<Lifetime> { e, l ->
            l.remaining -= dt
            if (l.remaining <= 0f) world.destroy(e)
        }
    }
}

/** Submits every visible [MeshRenderer] with a [Transform] to a 3D pass. */
object RenderSystem {
    private val m = Mat4()
    fun render(world: World, renderer: Renderer3D) {
        world.each<Transform, MeshRenderer> { _, t, r ->
            if (r.visible) renderer.draw(r.mesh, r.material, t.matrix(m), r.tint)
        }
    }
}
