package com.oea.engine.graphics

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.oea.engine.graphics.Gpu.genBuffer
import com.oea.engine.graphics.Gpu.genVertexArray
import com.oea.engine.math.Colors
import com.oea.engine.math.Vec3
import kotlin.math.sqrt

/**
 * A vertex-skinned mesh drawn with [Renderer3D]'s dedicated skin path
 * ([Renderer3D.drawSkinned]): bone indices ride along in the vertex stream
 * and the shader combines up to 4 bone matrices from the uniform palette the
 * skeleton uploads each frame.
 *
 * Stride is 20 floats: position 3, normal 3, uv 2, color 4 (linearized sRGB),
 * bone indices 4, bone weights 4. Skinned meshes own their VAO — the batched
 * pipeline can't host bone attributes, and the characters are few enough
 * (one arena = a few dozen) that per-mesh draws stay cheap.
 */
class SkeletalMesh(val vertices: FloatArray, val indices: IntArray) {
    val vertexCount: Int get() = vertices.size / STRIDE
    val indexCount: Int get() = indices.size
    val boundingRadius: Float get() = radius
    val center: Vec3 get() = _center
    val hasIndices: Boolean get() = indices.isNotEmpty()

    companion object {
        const val STRIDE = 20
    }

    /** Bounding sphere, baked at build time for culling. */
    private var radius = 0.05f
    private val _center = Vec3()
    private var minX = Float.MAX_VALUE; private var minY = Float.MAX_VALUE; private var minZ = Float.MAX_VALUE
    private var maxX = -Float.MAX_VALUE; private var maxY = -Float.MAX_VALUE; private var maxZ = -Float.MAX_VALUE

    private var vao = 0
    private var vbo = 0
    private var ibo = 0
    private var lastGeneration = -1
    private var dirty = false

    init {
        for (i in 0 until vertexCount step 3) {
            val x = vertices[i * STRIDE]; val y = vertices[i * STRIDE + 1]; val z = vertices[i * STRIDE + 2]
            if (x < minX) minX = x; if (y < minY) minY = y; if (z < minZ) minZ = z
            if (x > maxX) maxX = x; if (y > maxY) maxY = y; if (z > maxZ) maxZ = z
        }
        _center.set((minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f)
        val dx = maxX - minX; val dy = maxY - minY; val dz = maxZ - minZ
        radius = 0.5f * sqrt(dx * dx + dy * dy + dz * dz)
        dirty = true
    }

    /** GLES VAO upload. Returns false on failure. */
    internal fun bind(): Boolean {
        if (lastGeneration != Gpu.generation) {
            vao = 0; vbo = 0; ibo = 0
            lastGeneration = Gpu.generation
            dirty = true
        }
        if (vao == 0) {
            vao = genVertexArray()
            if (vao == 0) return false
            vbo = genBuffer()
            GLES30.glBindVertexArray(vao)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            val fb = Gpu.floatBuffer(vertices.size).put(vertices)
            fb.position(0)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.size * 4, fb, GLES30.GL_DYNAMIC_DRAW)
            if (indices.isNotEmpty()) {
                ibo = genBuffer()
                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
                val ib = Gpu.intBuffer(indices.size).put(indices)
                ib.position(0)
                GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.size * 4, ib, GLES30.GL_STATIC_DRAW)
            }
            attr(0, 3, 0)
            attr(1, 3, 12)
            attr(2, 2, 24)
            attr(3, 4, 32)
            attr(4, 4, 48)
            attr(5, 4, 64)
            GLES30.glBindVertexArray(0)
        } else {
            GLES30.glBindVertexArray(vao)
        }
        if (dirty) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            val fb = Gpu.floatBuffer(vertices.size).put(vertices)
            fb.position(0)
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, vertices.size * 4, fb)
            if (indices.isNotEmpty()) {
                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
                val ib = Gpu.intBuffer(indices.size).put(indices)
                ib.position(0)
                GLES30.glBufferSubData(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0, indices.size * 4, ib)
            }
            dirty = false
        }
        return true
    }

    private fun attr(loc: Int, size: Int, offset: Int) {
        GLES30.glEnableVertexAttribArray(loc)
        GLES30.glVertexAttribPointer(loc, size, GLES30.GL_FLOAT, false, STRIDE * 4, offset)
    }

    internal fun draw() {
        val type = if (vertexCount > 65535) GLES30.GL_UNSIGNED_INT else GLES30.GL_UNSIGNED_SHORT
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, type, 0)
    }

    internal fun dispose() {
        if (vao != 0) { GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0); vao = 0 }
        if (vbo != 0) { GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0); vbo = 0 }
        if (ibo != 0) { GLES30.glDeleteBuffers(1, intArrayOf(ibo), 0); ibo = 0 }
    }

    /** Accumulates vertices/indices and bakes them into a [SkeletalMesh]. */
    class Builder {
        private var buf = FloatArray(2048 * SkeletalMesh.STRIDE)
        private var verts = 0
        private var idx = IntArray(2048 * 3)
        private var tris = 0
        private val linear = FloatArray(4)

        val vertexCount: Int get() = verts
        private val capacity: Int get() = buf.size / SkeletalMesh.STRIDE

        /**
         * Adds a vertex. [boneWeights] is a weight→bone list (up to 4
         * entries) that is normalized and sorted by weight descending.
         * Returns the vertex index.
         */
        fun addVertex(
            x: Float, y: Float, z: Float,
            nx: Float, ny: Float, nz: Float,
            u: Float, v: Float,
            color: Int,
            boneWeights: List<Pair<Int, Float>>,
        ): Int {
            if (verts >= capacity) grow()
            val o = verts * SkeletalMesh.STRIDE
            Colors.toLinear(color, linear)
            buf[o] = x; buf[o + 1] = y; buf[o + 2] = z
            buf[o + 3] = nx; buf[o + 4] = ny; buf[o + 5] = nz
            buf[o + 6] = u; buf[o + 7] = v
            buf[o + 8] = linear[0]; buf[o + 9] = linear[1]; buf[o + 10] = linear[2]; buf[o + 11] = linear[3]
            // normalize weights (largest 4 kept, zeros allowed)
            val weights = boneWeights.sortedByDescending { it.second }.take(4)
            var total = 0f
            for (w in weights) total += w.second
            if (total <= 0f) total = 1f
            for (k in 0 until 4) {
                val w = weights.getOrNull(k)
                buf[o + 12 + k] = w?.first?.toFloat() ?: 0f
                buf[o + 16 + k] = (w?.second ?: 0f) / total
            }
            return verts++
        }

        /** Convenience: a vertex pinned to a single bone. */
        fun addVertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, u: Float, v: Float, color: Int, bone: Int): Int =
            addVertex(x, y, z, nx, ny, nz, u, v, color, listOf(bone to 1f))

        fun tri(a: Int, b: Int, c: Int) {
            if (tris >= idx.size - 3) grow()
            idx[tris++] = a; idx[tris++] = b; idx[tris++] = c
        }

        private fun grow() {
            val nv = maxOf(verts + 1, buf.size / SkeletalMesh.STRIDE * 2)
            buf = buf.copyOf(nv * SkeletalMesh.STRIDE)
            val nt = maxOf(tris + 3, idx.size * 2)
            idx = idx.copyOf(nt)
        }

        fun build(): SkeletalMesh {
            val v = buf.copyOf(verts * SkeletalMesh.STRIDE)
            val i = idx.copyOf(tris)
            return SkeletalMesh(v, i)
        }
    }
}
