package com.oea.engine.graphics

import android.opengl.GLES30
import com.oea.engine.math.Aabb
import com.oea.engine.math.Colors
import com.oea.engine.math.Mat4
import com.oea.engine.math.MathX
import com.oea.engine.math.Vec2
import com.oea.engine.math.Vec3
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Indexed triangle mesh. Vertex layout (12 floats): position xyz, normal xyz,
 * uv, colour rgba (linear). Data stays on the CPU side too, so the mesh
 * survives GPU context loss and can be edited with [update].
 *
 * Attribute locations: 0 position, 1 normal, 2 uv, 3 colour; 4–7 instance
 * model matrix, 8 instance tint (set by the renderer).
 */
class Mesh(vertices: FloatArray, indices: IntArray? = null, val primitive: Int = GLES30.GL_TRIANGLES) {
    val id = ids.incrementAndGet()
    var vertices: FloatArray = vertices
        private set
    var indices: IntArray? = indices
        private set
    val vertexCount get() = vertices.size / STRIDE
    val indexCount get() = indices?.size ?: 0
    val bounds = Aabb()
    val center = Vec3()
    var radius = 0f
        private set

    private var vao = 0
    private var vbo = 0
    private var ibo = 0
    private var generation = -1
    private var dirty = true
    private var intIndices = false

    init { computeBounds() }

    /** Replaces the geometry; re-uploaded on next draw. */
    fun update(vertices: FloatArray, indices: IntArray? = this.indices) {
        this.vertices = vertices
        this.indices = indices
        dirty = true
        computeBounds()
    }

    private fun computeBounds() {
        bounds.empty()
        val n = vertexCount
        if (n == 0) { bounds.set(0f, 0f, 0f, 0f, 0f, 0f); center.setZero(); radius = 0f; return }
        for (i in 0 until n) bounds.extend(vertices[i * STRIDE], vertices[i * STRIDE + 1], vertices[i * STRIDE + 2])
        center.set(bounds.centerX, bounds.centerY, bounds.centerZ)
        var r2 = 0f
        for (i in 0 until n) {
            val dx = vertices[i * STRIDE] - center.x; val dy = vertices[i * STRIDE + 1] - center.y; val dz = vertices[i * STRIDE + 2] - center.z
            r2 = maxOf(r2, dx * dx + dy * dy + dz * dz)
        }
        radius = sqrt(r2)
    }

    /** Binds the VAO, uploading first if needed. GL thread only. */
    internal fun bind(): Boolean {
        if (generation != Gpu.generation) {
            vao = 0; vbo = 0; ibo = 0
            generation = Gpu.generation
            dirty = true
        }
        if (vao == 0) {
            vao = Gpu.genVertexArray()
            vbo = Gpu.genBuffer()
            GLES30.glBindVertexArray(vao)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            val stride = STRIDE * 4
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
            GLES30.glEnableVertexAttribArray(2)
            GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, stride, 24)
            GLES30.glEnableVertexAttribArray(3)
            GLES30.glVertexAttribPointer(3, 4, GLES30.GL_FLOAT, false, stride, 32)
            if (indices != null) {
                ibo = Gpu.genBuffer()
                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
            }
        } else {
            GLES30.glBindVertexArray(vao)
        }
        if (dirty) upload()
        return vertexCount > 0
    }

    private fun upload() {
        dirty = false
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        val fb = Gpu.floatBuffer(vertices.size).put(vertices)
        fb.position(0)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.size * 4, fb, GLES30.GL_STATIC_DRAW)
        val idx = indices
        if (idx != null) {
            if (ibo == 0) ibo = Gpu.genBuffer()
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
            intIndices = vertexCount > 65535
            if (intIndices) {
                val ib = Gpu.intBuffer(idx.size).put(idx)
                ib.position(0)
                GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, idx.size * 4, ib, GLES30.GL_STATIC_DRAW)
            } else {
                val sb = Gpu.shortBuffer(idx.size)
                for (i in idx) sb.put(i.toShort())
                sb.position(0)
                GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, idx.size * 2, sb, GLES30.GL_STATIC_DRAW)
            }
        }
    }

    /** Points instance attributes (model matrix + tint) at [instanceVbo] starting at [byteOffset]. VAO must be bound. */
    internal fun bindInstances(instanceVbo: Int, byteOffset: Int) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
        for (i in 0 until 4) {
            GLES30.glEnableVertexAttribArray(4 + i)
            GLES30.glVertexAttribPointer(4 + i, 4, GLES30.GL_FLOAT, false, INSTANCE_BYTES, byteOffset + i * 16)
            GLES30.glVertexAttribDivisor(4 + i, 1)
        }
        GLES30.glEnableVertexAttribArray(8)
        GLES30.glVertexAttribPointer(8, 4, GLES30.GL_FLOAT, false, INSTANCE_BYTES, byteOffset + 64)
        GLES30.glVertexAttribDivisor(8, 1)
    }

    internal fun drawInstanced(count: Int) {
        if (indices != null) {
            GLES30.glDrawElementsInstanced(primitive, indexCount, if (intIndices) GLES30.GL_UNSIGNED_INT else GLES30.GL_UNSIGNED_SHORT, 0, count)
        } else {
            GLES30.glDrawArraysInstanced(primitive, 0, vertexCount, count)
        }
    }

    /** Frees GPU memory (GL thread). The mesh re-uploads if drawn again. */
    fun dispose() {
        if (generation == Gpu.generation) {
            Gpu.deleteVertexArray(vao); Gpu.deleteBuffer(vbo); Gpu.deleteBuffer(ibo)
        }
        vao = 0; vbo = 0; ibo = 0
        dirty = true
    }

    companion object {
        const val STRIDE = 12
        /** Per-instance data: mat4 + tint = 20 floats. */
        const val INSTANCE_FLOATS = 20
        const val INSTANCE_BYTES = INSTANCE_FLOATS * 4
        private val ids = AtomicInteger()

        /** Shared unit primitives, created on demand. */
        val unitCube: Mesh by lazy { MeshBuilder().box(0f, 0f, 0f, 1f, 1f, 1f).build() }
        val unitSphere: Mesh by lazy { MeshBuilder().sphere(0f, 0f, 0f, 0.5f, 24, 16).build() }
        /** Cylinder of radius 0.5 and height 1, base at y = 0. */
        val unitCylinder: Mesh by lazy { MeshBuilder().cylinder(0f, 0f, 0f, 0.5f, 1f, 16).build() }
        /** Upward-facing unit quad in XZ, centred. */
        val unitQuad: Mesh by lazy { MeshBuilder().plane(0f, 0f, 0f, 1f, 1f).build() }
    }
}

/**
 * Builds meshes from primitives, profiles and custom vertices. Every
 * primitive honours the current [color] and optional [transform].
 */
class MeshBuilder {
    private var v = FloatArray(Mesh.STRIDE * 256)
    private var vCount = 0
    private var idx = IntArray(768)
    private var iCount = 0
    private val lin = FloatArray(4)
    private val tmpP = Vec3()
    private val tmpN = Vec3()

    /** Vertex colour (sRGB ARGB) for subsequently added geometry. */
    var color: Int = Colors.WHITE
        set(value) { field = value; Colors.toLinear(value, lin) }

    /** Optional transform applied to subsequently added geometry. */
    var transform: Mat4? = null

    init { Colors.toLinear(color, lin) }

    val vertexCount get() = vCount

    fun clear(): MeshBuilder { vCount = 0; iCount = 0; return this }

    /** Adds one vertex; returns its index. */
    fun vertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, u: Float = 0f, vv: Float = 0f): Int {
        if ((vCount + 1) * Mesh.STRIDE > v.size) v = v.copyOf(v.size * 2)
        tmpP.set(x, y, z); tmpN.set(nx, ny, nz)
        transform?.let { t -> t.transformPoint(tmpP); t.transformDir(tmpN).nor() }
        val o = vCount * Mesh.STRIDE
        v[o] = tmpP.x; v[o + 1] = tmpP.y; v[o + 2] = tmpP.z
        v[o + 3] = tmpN.x; v[o + 4] = tmpN.y; v[o + 5] = tmpN.z
        v[o + 6] = u; v[o + 7] = vv
        v[o + 8] = lin[0]; v[o + 9] = lin[1]; v[o + 10] = lin[2]; v[o + 11] = lin[3]
        return vCount++
    }

    fun triangle(a: Int, b: Int, c: Int): MeshBuilder {
        if (iCount + 3 > idx.size) idx = idx.copyOf(idx.size * 2)
        idx[iCount++] = a; idx[iCount++] = b; idx[iCount++] = c
        return this
    }

    /** Quad a-b-c-d in counter-clockwise order (front face). */
    fun quad(a: Int, b: Int, c: Int, d: Int): MeshBuilder { triangle(a, b, c); return triangle(a, c, d) }

    /** Axis-aligned box centred at (cx, cy, cz) with full sizes (sx, sy, sz); UVs span each face. */
    fun box(cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float, uvScale: Float = 0f): MeshBuilder {
        val hx = sx / 2f; val hy = sy / 2f; val hz = sz / 2f
        fun face(nx: Float, ny: Float, nz: Float, ux: Float, uy: Float, uz: Float, vx: Float, vy: Float, vz: Float, w: Float, h: Float) {
            // Corner = centre + n*depth ± u*hw ± v*hh
            val depth = abs(nx) * hx + abs(ny) * hy + abs(nz) * hz
            val ox = cx + nx * depth; val oy = cy + ny * depth; val oz = cz + nz * depth
            val hw = w / 2f; val hh = h / 2f
            val us = if (uvScale > 0f) w * uvScale else 1f
            val vs = if (uvScale > 0f) h * uvScale else 1f
            val a = vertex(ox - ux * hw - vx * hh, oy - uy * hw - vy * hh, oz - uz * hw - vz * hh, nx, ny, nz, 0f, vs)
            val b = vertex(ox + ux * hw - vx * hh, oy + uy * hw - vy * hh, oz + uz * hw - vz * hh, nx, ny, nz, us, vs)
            val c = vertex(ox + ux * hw + vx * hh, oy + uy * hw + vy * hh, oz + uz * hw + vz * hh, nx, ny, nz, us, 0f)
            val d = vertex(ox - ux * hw + vx * hh, oy - uy * hw + vy * hh, oz - uz * hw + vz * hh, nx, ny, nz, 0f, 0f)
            quad(a, b, c, d)
        }
        face(0f, 0f, 1f, 1f, 0f, 0f, 0f, 1f, 0f, sx, sy)     // +Z
        face(0f, 0f, -1f, -1f, 0f, 0f, 0f, 1f, 0f, sx, sy)   // -Z
        face(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f, sz, sy)    // +X
        face(-1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f, sz, sy)    // -X
        face(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, -1f, sx, sz)    // +Y
        face(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, sx, sz)    // -Y
        return this
    }

    /** Box from min/max corners. */
    fun boxMinMax(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, uvScale: Float = 0f): MeshBuilder =
        box((x0 + x1) / 2f, (y0 + y1) / 2f, (z0 + z1) / 2f, x1 - x0, y1 - y0, z1 - z0, uvScale)

    /**
     * Upward-facing grid in XZ. With [uvPerUnit] = 0 the texture spans the
     * whole plane; otherwise it repeats [uvPerUnit] times per world unit.
     */
    fun plane(cx: Float, cy: Float, cz: Float, sx: Float, sz: Float, segX: Int = 1, segZ: Int = 1, uvPerUnit: Float = 0f): MeshBuilder {
        val base = vCount
        val us = if (uvPerUnit > 0f) sx * uvPerUnit else 1f
        val vs = if (uvPerUnit > 0f) sz * uvPerUnit else 1f
        for (j in 0..segZ) for (i in 0..segX) {
            val fx = i / segX.toFloat(); val fz = j / segZ.toFloat()
            vertex(cx - sx / 2f + sx * fx, cy, cz - sz / 2f + sz * fz, 0f, 1f, 0f, fx * us, fz * vs)
        }
        for (j in 0 until segZ) for (i in 0 until segX) {
            val a = base + j * (segX + 1) + i
            val b = a + 1
            val c = a + segX + 2
            val d = a + segX + 1
            quad(a, d, c, b)
        }
        return this
    }

    fun sphere(cx: Float, cy: Float, cz: Float, r: Float, segments: Int = 24, rings: Int = 16): MeshBuilder {
        val base = vCount
        for (j in 0..rings) {
            val phi = MathX.PI_F * j / rings
            val y = cos(phi); val sr = sin(phi)
            for (i in 0..segments) {
                val th = MathX.TAU * i / segments
                val x = sr * sin(th); val z = sr * cos(th)
                vertex(cx + x * r, cy + y * r, cz + z * r, x, y, z, i / segments.toFloat(), j / rings.toFloat())
            }
        }
        for (j in 0 until rings) for (i in 0 until segments) {
            val a = base + j * (segments + 1) + i
            val b = a + segments + 1
            quad(a, b, b + 1, a + 1)
        }
        return this
    }

    /** Y-up cylinder with its base centre at (cx, cy, cz). */
    fun cylinder(cx: Float, cy: Float, cz: Float, radius: Float, height: Float, segments: Int = 16, caps: Boolean = true, topRadius: Float = radius): MeshBuilder {
        val profile = listOf(Vec2(radius, 0f), Vec2(topRadius, height))
        val t = transform
        val local = Mat4().setTranslation(cx, cy, cz)
        transform = if (t != null) Mat4().mul(t, local) else local
        lathe(profile, segments, capBottom = caps, capTop = caps && topRadius > 0f)
        transform = t
        return this
    }

    fun cone(cx: Float, cy: Float, cz: Float, radius: Float, height: Float, segments: Int = 16): MeshBuilder =
        cylinder(cx, cy, cz, radius, height, segments, caps = true, topRadius = 0f)

    /** Y-up capsule: total height includes the hemispheres. */
    fun capsule(cx: Float, cy: Float, cz: Float, radius: Float, height: Float, segments: Int = 16): MeshBuilder {
        val half = maxOf(0f, height / 2f - radius)
        val profile = ArrayList<Vec2>()
        val steps = 6
        for (i in 0..steps) {
            val a = -MathX.HALF_PI + MathX.HALF_PI * i / steps
            profile += Vec2(cos(a) * radius, -half + sin(a) * radius)
        }
        for (i in 0..steps) {
            val a = MathX.HALF_PI * i / steps
            profile += Vec2(cos(a) * radius, half + sin(a) * radius)
        }
        val t = transform
        val local = Mat4().setTranslation(cx, cy + height / 2f, cz)
        transform = if (t != null) Mat4().mul(t, local) else local
        lathe(profile, segments, capBottom = false, capTop = false, creaseDegrees = 89f)
        transform = t
        return this
    }

    fun torus(cx: Float, cy: Float, cz: Float, major: Float, minor: Float, segments: Int = 24, sides: Int = 12): MeshBuilder {
        val base = vCount
        for (i in 0..segments) {
            val u = MathX.TAU * i / segments
            val cu = cos(u); val su = sin(u)
            for (j in 0..sides) {
                val w = MathX.TAU * j / sides
                val cw = cos(w); val sw = sin(w)
                val nx = cu * cw; val ny = sw; val nz = su * cw
                vertex(cx + (major + minor * cw) * cu, cy + minor * sw, cz + (major + minor * cw) * su, nx, ny, nz, i / segments.toFloat(), j / sides.toFloat())
            }
        }
        for (i in 0 until segments) for (j in 0 until sides) {
            val a = base + i * (sides + 1) + j
            val b = a + sides + 1
            quad(a, a + 1, b + 1, b)
        }
        return this
    }

    /**
     * Surface of revolution around Y. [profile] points are (radius, y) from
     * bottom to top. Corners sharper than [creaseDegrees] keep hard normals,
     * so one profile can mix smooth curves and crisp edges (chess pieces, vases).
     */
    fun lathe(profile: List<Vec2>, segments: Int = 24, capBottom: Boolean = true, capTop: Boolean = true, creaseDegrees: Float = 35f): MeshBuilder {
        if (profile.size < 2) return this
        val n = profile.size
        // Segment normals in profile space (outward): (dy, -dr) normalised.
        val segN = Array(n - 1) { i ->
            val dr = profile[i + 1].x - profile[i].x
            val dy = profile[i + 1].y - profile[i].y
            Vec2(dy, -dr).nor()
        }
        val creaseCos = cos(creaseDegrees * MathX.DEG_TO_RAD)
        var totalLen = 0f
        val lens = FloatArray(n)
        for (i in 1 until n) { totalLen += profile[i].dst(profile[i - 1]); lens[i] = totalLen }
        for (s in 0 until n - 1) {
            val nA = vertexNormal(segN, s, s, creaseCos)
            val nB = vertexNormal(segN, s + 1, s, creaseCos)
            val p0 = profile[s]; val p1 = profile[s + 1]
            val base = vCount
            for (i in 0..segments) {
                val th = MathX.TAU * i / segments
                val c = cos(th); val sn = sin(th)
                val u = i / segments.toFloat()
                vertex(p0.x * sn, p0.y, p0.x * c, nA.x * sn, nA.y, nA.x * c, u, 1f - lens[s] / maxOf(totalLen, 1e-5f))
                vertex(p1.x * sn, p1.y, p1.x * c, nB.x * sn, nB.y, nB.x * c, u, 1f - lens[s + 1] / maxOf(totalLen, 1e-5f))
            }
            for (i in 0 until segments) {
                val a = base + i * 2
                quad(a, a + 2, a + 3, a + 1)
            }
        }
        if (capBottom && profile.first().x > 1e-4f) disc(profile.first().x, profile.first().y, segments, up = false)
        if (capTop && profile.last().x > 1e-4f) disc(profile.last().x, profile.last().y, segments, up = true)
        return this
    }

    private fun vertexNormal(segN: Array<Vec2>, vertexIndex: Int, segment: Int, creaseCos: Float): Vec2 {
        val own = segN[segment]
        val otherSeg = if (vertexIndex == segment) segment - 1 else segment + 1
        if (otherSeg < 0 || otherSeg >= segN.size) return own
        val other = segN[otherSeg]
        if (own.dot(other) < creaseCos) return own
        return Vec2(own.x + other.x, own.y + other.y).nor()
    }

    private fun disc(r: Float, y: Float, segments: Int, up: Boolean) {
        val ny = if (up) 1f else -1f
        val centre = vertex(0f, y, 0f, 0f, ny, 0f, 0.5f, 0.5f)
        val base = vCount
        for (i in 0..segments) {
            val th = MathX.TAU * i / segments
            vertex(r * sin(th), y, r * cos(th), 0f, ny, 0f, 0.5f + 0.5f * sin(th), 0.5f + 0.5f * cos(th))
        }
        for (i in 0 until segments) {
            if (up) triangle(centre, base + i, base + i + 1) else triangle(centre, base + i + 1, base + i)
        }
    }

    /**
     * Extrudes a simple polygon (counter-clockwise, XY plane) along Z, centred
     * on z = 0. Caps are triangulated by ear clipping.
     */
    fun extrude(polygon: List<Vec2>, depth: Float): MeshBuilder {
        if (polygon.size < 3) return this
        val pts = if (signedArea(polygon) < 0f) polygon.reversed() else polygon
        val tris = triangulate(pts)
        val hz = depth / 2f
        // Front (+Z)
        var base = vCount
        for (p in pts) vertex(p.x, p.y, hz, 0f, 0f, 1f, p.x, p.y)
        for (i in tris.indices step 3) triangle(base + tris[i], base + tris[i + 1], base + tris[i + 2])
        // Back (-Z)
        base = vCount
        for (p in pts) vertex(p.x, p.y, -hz, 0f, 0f, -1f, p.x, p.y)
        for (i in tris.indices step 3) triangle(base + tris[i], base + tris[i + 2], base + tris[i + 1])
        // Sides
        for (i in pts.indices) {
            val a = pts[i]; val b = pts[(i + 1) % pts.size]
            val ex = b.x - a.x; val ey = b.y - a.y
            val l = sqrt(ex * ex + ey * ey).coerceAtLeast(1e-6f)
            val nx = ey / l; val ny = -ex / l
            val v0 = vertex(a.x, a.y, hz, nx, ny, 0f, 0f, 0f)
            val v1 = vertex(a.x, a.y, -hz, nx, ny, 0f, 0f, 1f)
            val v2 = vertex(b.x, b.y, -hz, nx, ny, 0f, 1f, 1f)
            val v3 = vertex(b.x, b.y, hz, nx, ny, 0f, 1f, 0f)
            quad(v0, v1, v2, v3)
        }
        return this
    }

    /** Appends another mesh's geometry (optionally transformed by [m]). */
    fun add(mesh: Mesh, m: Mat4? = null): MeshBuilder {
        val src = mesh.vertices
        val base = vCount
        val saved = transform
        transform = when {
            m == null -> saved
            saved == null -> m
            else -> Mat4().mul(saved, m)
        }
        val savedLin = lin.copyOf()
        for (i in 0 until mesh.vertexCount) {
            val o = i * Mesh.STRIDE
            lin[0] = src[o + 8] * savedLin[0]; lin[1] = src[o + 9] * savedLin[1]; lin[2] = src[o + 10] * savedLin[2]; lin[3] = src[o + 11] * savedLin[3]
            vertex(src[o], src[o + 1], src[o + 2], src[o + 3], src[o + 4], src[o + 5], src[o + 6], src[o + 7])
        }
        savedLin.copyInto(lin)
        transform = saved
        val ind = mesh.indices
        if (ind != null) {
            var i = 0
            while (i + 2 < ind.size) { triangle(base + ind[i], base + ind[i + 1], base + ind[i + 2]); i += 3 }
        } else {
            var i = 0
            while (i + 2 < mesh.vertexCount) { triangle(base + i, base + i + 1, base + i + 2); i += 3 }
        }
        return this
    }

    /** Runs [block] with [m] composed onto the current transform. */
    inline fun withTransform(m: Mat4, block: MeshBuilder.() -> Unit): MeshBuilder {
        val saved = transform
        transform = if (saved == null) m else Mat4().mul(saved, m)
        block()
        transform = saved
        return this
    }

    /** Recomputes smooth normals from triangle faces (useful for custom geometry). */
    fun computeNormals(): MeshBuilder {
        for (i in 0 until vCount) { val o = i * Mesh.STRIDE; v[o + 3] = 0f; v[o + 4] = 0f; v[o + 5] = 0f }
        var t = 0
        while (t + 2 < iCount) {
            val a = idx[t] * Mesh.STRIDE; val b = idx[t + 1] * Mesh.STRIDE; val c = idx[t + 2] * Mesh.STRIDE
            val e1x = v[b] - v[a]; val e1y = v[b + 1] - v[a + 1]; val e1z = v[b + 2] - v[a + 2]
            val e2x = v[c] - v[a]; val e2y = v[c + 1] - v[a + 1]; val e2z = v[c + 2] - v[a + 2]
            val nx = e1y * e2z - e1z * e2y; val ny = e1z * e2x - e1x * e2z; val nz = e1x * e2y - e1y * e2x
            for (o in intArrayOf(a, b, c)) { v[o + 3] += nx; v[o + 4] += ny; v[o + 5] += nz }
            t += 3
        }
        for (i in 0 until vCount) {
            val o = i * Mesh.STRIDE
            val l = MathX.length(v[o + 3], v[o + 4], v[o + 5]).coerceAtLeast(1e-8f)
            v[o + 3] /= l; v[o + 4] /= l; v[o + 5] /= l
        }
        return this
    }

    fun build(): Mesh = Mesh(v.copyOf(vCount * Mesh.STRIDE), idx.copyOf(iCount))

    companion object {
        fun signedArea(p: List<Vec2>): Float {
            var a = 0f
            for (i in p.indices) { val q = p[(i + 1) % p.size]; a += p[i].x * q.y - q.x * p[i].y }
            return a / 2f
        }

        /** Ear-clipping triangulation of a simple CCW polygon; returns index triples. */
        fun triangulate(p: List<Vec2>): IntArray {
            val n = p.size
            if (n < 3) return IntArray(0)
            val remaining = (0 until n).toMutableList()
            val out = ArrayList<Int>()
            var guard = 0
            while (remaining.size > 3 && guard++ < n * n) {
                var clipped = false
                for (i in remaining.indices) {
                    val ia = remaining[(i + remaining.size - 1) % remaining.size]
                    val ib = remaining[i]
                    val ic = remaining[(i + 1) % remaining.size]
                    val a = p[ia]; val b = p[ib]; val c = p[ic]
                    if ((b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x) <= 1e-9f) continue // reflex
                    var inside = false
                    for (j in remaining) {
                        if (j == ia || j == ib || j == ic) continue
                        if (pointInTriangle(p[j], a, b, c)) { inside = true; break }
                    }
                    if (inside) continue
                    out += ia; out += ib; out += ic
                    remaining.removeAt(i)
                    clipped = true
                    break
                }
                if (!clipped) break
            }
            if (remaining.size == 3) { out += remaining[0]; out += remaining[1]; out += remaining[2] }
            return out.toIntArray()
        }

        private fun pointInTriangle(p: Vec2, a: Vec2, b: Vec2, c: Vec2): Boolean {
            val d1 = (p.x - b.x) * (a.y - b.y) - (a.x - b.x) * (p.y - b.y)
            val d2 = (p.x - c.x) * (b.y - c.y) - (b.x - c.x) * (p.y - c.y)
            val d3 = (p.x - a.x) * (c.y - a.y) - (c.x - a.x) * (p.y - a.y)
            val neg = d1 < 0f || d2 < 0f || d3 < 0f
            val pos = d1 > 0f || d2 > 0f || d3 > 0f
            return !(neg && pos)
        }

        @Suppress("unused") private fun angle(a: Vec2, b: Vec2) = acos(MathX.clamp(a.dot(b), -1f, 1f))
    }
}

/** Utility to stream instance data without allocating per frame. */
internal class InstanceStream(initialInstances: Int = 256) {
    var data = FloatArray(initialInstances * Mesh.INSTANCE_FLOATS)
        private set
    var count = 0
        private set
    private var buffer: FloatBuffer = Gpu.floatBuffer(data.size)
    private var vbo = 0
    private var generation = -1
    private var capacityBytes = 0

    fun reset() { count = 0 }

    /** Appends one instance; returns its index. */
    fun add(matrix: FloatArray, matrixOffset: Int, tint: FloatArray, tintOffset: Int): Int {
        if ((count + 1) * Mesh.INSTANCE_FLOATS > data.size) data = data.copyOf(data.size * 2)
        val o = count * Mesh.INSTANCE_FLOATS
        System.arraycopy(matrix, matrixOffset, data, o, 16)
        System.arraycopy(tint, tintOffset, data, o + 16, 4)
        return count++
    }

    /** Uploads all instances; returns the VBO id. */
    fun upload(): Int {
        if (generation != Gpu.generation) { vbo = 0; generation = Gpu.generation; capacityBytes = 0 }
        if (vbo == 0) vbo = Gpu.genBuffer()
        val floats = count * Mesh.INSTANCE_FLOATS
        if (buffer.capacity() < floats) buffer = Gpu.floatBuffer(data.size)
        buffer.position(0)
        buffer.put(data, 0, floats)
        buffer.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        val bytes = floats * 4
        if (bytes > capacityBytes) {
            capacityBytes = maxOf(bytes, capacityBytes * 2)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, capacityBytes, null, GLES30.GL_STREAM_DRAW)
        } else {
            // Orphan the old storage so the driver never stalls on in-flight frames.
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, capacityBytes, null, GLES30.GL_STREAM_DRAW)
        }
        if (bytes > 0) GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, bytes, buffer)
        return vbo
    }
}
