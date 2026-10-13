package com.oea.engine.math.nd

import com.oea.engine.math.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * N-dimensional geometry. The renderer draws 2D and 3D natively; anything with
 * more dimensions is described here (points, rotations in any plane, meshes)
 * and projected down one dimension at a time until it reaches 3D. A 4D game
 * therefore needs no renderer changes: simulate in `VecN(4)`, project with a
 * [HyperProjector], draw the result with the normal 3D renderer.
 */
class VecN(val dim: Int) {
    val c = FloatArray(dim)

    constructor(vararg values: Float) : this(values.size) { values.copyInto(c) }

    operator fun get(i: Int) = c[i]
    operator fun set(i: Int, v: Float) { c[i] = v }
    fun set(o: VecN): VecN { o.c.copyInto(c, 0, 0, minOf(dim, o.dim)); return this }
    fun add(o: VecN): VecN { for (i in 0 until minOf(dim, o.dim)) c[i] += o.c[i]; return this }
    fun sub(o: VecN): VecN { for (i in 0 until minOf(dim, o.dim)) c[i] -= o.c[i]; return this }
    fun scl(s: Float): VecN { for (i in c.indices) c[i] *= s; return this }
    fun dot(o: VecN): Float { var s = 0f; for (i in 0 until minOf(dim, o.dim)) s += c[i] * o.c[i]; return s }
    fun len(): Float = sqrt(dot(this))
    fun nor(): VecN { val l = len(); if (l > 1e-6f) scl(1f / l); return this }
    fun copy(): VecN = VecN(dim).also { c.copyInto(it.c) }
    override fun toString() = c.joinToString(prefix = "(", postfix = ")")
}

/** Row-major N×N matrix; used for rotations in N-dimensional space. */
class MatN(val dim: Int) {
    val m = FloatArray(dim * dim)

    init { idt() }

    fun idt(): MatN {
        m.fill(0f)
        for (i in 0 until dim) m[i * dim + i] = 1f
        return this
    }

    operator fun get(r: Int, c: Int) = m[r * dim + c]
    operator fun set(r: Int, c: Int, v: Float) { m[r * dim + c] = v }

    /** this = this * o */
    fun mul(o: MatN): MatN {
        val out = FloatArray(dim * dim)
        for (r in 0 until dim) for (c in 0 until dim) {
            var s = 0f
            for (k in 0 until dim) s += m[r * dim + k] * o.m[k * dim + c]
            out[r * dim + c] = s
        }
        out.copyInto(m)
        return this
    }

    /**
     * Composes a rotation by [angle] in the plane spanned by axes [a] and [b].
     * In 3D the planes XY, XZ, YZ are the familiar roll/yaw/pitch; 4D adds
     * XW, YW and ZW, and so on (N·(N−1)/2 planes in N dimensions).
     */
    fun rotatePlane(a: Int, b: Int, angle: Float): MatN {
        require(a in 0 until dim && b in 0 until dim && a != b) { "Invalid rotation plane $a-$b for $dim dimensions" }
        val r = MatN(dim)
        val cs = cos(angle); val sn = sin(angle)
        r[a, a] = cs; r[a, b] = -sn
        r[b, a] = sn; r[b, b] = cs
        return mul(r)
    }

    /** out = this · v */
    fun transform(v: FloatArray, out: FloatArray) {
        for (r in 0 until dim) {
            var s = 0f
            for (k in 0 until dim) s += m[r * dim + k] * v[k]
            out[r] = s
        }
    }

    /** Re-orthonormalises (Gram–Schmidt) to remove drift after many incremental rotations. */
    fun orthonormalize(): MatN {
        for (col in 0 until dim) {
            for (prev in 0 until col) {
                var d = 0f
                for (r in 0 until dim) d += m[r * dim + col] * m[r * dim + prev]
                for (r in 0 until dim) m[r * dim + col] -= d * m[r * dim + prev]
            }
            var l = 0f
            for (r in 0 until dim) l += m[r * dim + col] * m[r * dim + col]
            l = sqrt(l)
            if (l > 1e-6f) for (r in 0 until dim) m[r * dim + col] /= l
        }
        return this
    }

    companion object {
        /** Number of independent rotation planes in [dim] dimensions. */
        fun planeCount(dim: Int) = dim * (dim - 1) / 2

        /** Axis pair of the [index]-th rotation plane (lexicographic order). */
        fun plane(dim: Int, index: Int): Pair<Int, Int> {
            var i = index
            for (a in 0 until dim) for (b in a + 1 until dim) { if (i == 0) return a to b; i-- }
            throw IllegalArgumentException("Plane $index out of range for $dim dimensions")
        }

        fun axisName(axis: Int): String = when (axis) { 0 -> "X"; 1 -> "Y"; 2 -> "Z"; 3 -> "W"; 4 -> "V"; 5 -> "U"; else -> "A$axis" }
    }
}

/** Vertices and edges in N dimensions. Faces are optional (lists of vertex indices). */
class HyperMesh(val dim: Int, val vertices: List<FloatArray>, val edges: List<IntArray>, val faces: List<IntArray> = emptyList()) {
    init {
        require(vertices.all { it.size == dim }) { "Every vertex must have $dim coordinates" }
    }
    val vertexCount get() = vertices.size
}

/** Regular polytopes that exist in every dimension. */
object Polytopes {
    /** N-cube (square, cube, tesseract, penteract ...) with edge length [size]. */
    fun hypercube(dim: Int, size: Float = 1f): HyperMesh {
        val n = 1 shl dim
        val h = size / 2f
        val verts = (0 until n).map { bits -> FloatArray(dim) { axis -> if ((bits shr axis) and 1 == 1) h else -h } }
        val edges = ArrayList<IntArray>()
        for (i in 0 until n) for (axis in 0 until dim) {
            val j = i xor (1 shl axis)
            if (j > i) edges += intArrayOf(i, j)
        }
        // 2-faces: for every pair of axes, the squares spanned by them.
        val faces = ArrayList<IntArray>()
        for (a in 0 until dim) for (b in a + 1 until dim) for (base in 0 until n) {
            if ((base shr a) and 1 == 0 && (base shr b) and 1 == 0) {
                faces += intArrayOf(base, base or (1 shl a), base or (1 shl a) or (1 shl b), base or (1 shl b))
            }
        }
        return HyperMesh(dim, verts, edges, faces)
    }

    /** N-simplex (triangle, tetrahedron, 5-cell ...), centred at the origin. */
    fun simplex(dim: Int, size: Float = 1f): HyperMesh {
        // Standard construction: dim+1 points; start with unit basis vectors in dim+1 space, then project to dim.
        val count = dim + 1
        val pts = ArrayList<FloatArray>()
        for (i in 0 until count) {
            val p = FloatArray(dim)
            if (i < dim) p[i] = 1f else {
                val v = (1f - sqrt((dim + 1).toFloat())) / dim
                for (k in 0 until dim) p[k] = v
            }
            pts += p
        }
        val centroid = FloatArray(dim)
        pts.forEach { p -> for (k in 0 until dim) centroid[k] += p[k] / count }
        val scale = size / sqrt(2f)
        val verts = pts.map { p -> FloatArray(dim) { k -> (p[k] - centroid[k]) * scale } }
        val edges = ArrayList<IntArray>()
        for (i in 0 until count) for (j in i + 1 until count) edges += intArrayOf(i, j)
        val faces = ArrayList<IntArray>()
        for (i in 0 until count) for (j in i + 1 until count) for (k in j + 1 until count) faces += intArrayOf(i, j, k)
        return HyperMesh(dim, verts, edges, faces)
    }

    /** N-orthoplex (diamond, octahedron, 16-cell ...). */
    fun crossPolytope(dim: Int, size: Float = 1f): HyperMesh {
        val verts = ArrayList<FloatArray>()
        for (axis in 0 until dim) {
            verts += FloatArray(dim).also { it[axis] = size }
            verts += FloatArray(dim).also { it[axis] = -size }
        }
        val edges = ArrayList<IntArray>()
        for (i in verts.indices) for (j in i + 1 until verts.size) {
            if (i / 2 != j / 2) edges += intArrayOf(i, j)
        }
        return HyperMesh(dim, verts, edges)
    }
}

/**
 * Projects N-dimensional points to 3D: each extra dimension is removed by a
 * perspective divide (viewer at [viewDistance] on that axis) or by dropping it
 * when [perspective] is false. [depth] receives the normalised position along
 * the first hidden axis (e.g. W in 4D) for colouring.
 */
class HyperProjector(var viewDistance: Float = 3f, var perspective: Boolean = true) {
    private var scratch = FloatArray(8)

    fun project(point: FloatArray, out: Vec3): Float {
        val dim = point.size
        if (scratch.size < dim) scratch = FloatArray(dim)
        point.copyInto(scratch)
        var depth = 0f
        var d = dim
        while (d > 3) {
            val w = scratch[d - 1]
            if (d == 4 || depth == 0f) depth = w
            if (perspective) {
                val denom = viewDistance - w
                val f = if (abs(denom) < 1e-3f) 1e3f else viewDistance / denom
                for (k in 0 until d - 1) scratch[k] *= f
            }
            d--
        }
        out.set(scratch[0], if (dim > 1) scratch[1] else 0f, if (dim > 2) scratch[2] else 0f)
        return depth
    }
}

/** An object living in N-dimensional space: position, rotation and uniform scale. */
class HyperTransform(val dim: Int) {
    val position = VecN(dim)
    val rotation = MatN(dim)
    var scale = 1f
    private val tmp = FloatArray(dim)

    /** World-space coordinates of a local vertex. */
    fun apply(local: FloatArray, out: FloatArray) {
        rotation.transform(local, tmp)
        for (i in 0 until dim) out[i] = tmp[i] * scale + position.c[i]
    }
}
