package com.oea.engine.graphics

import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.ShortBuffer

/**
 * GPU context bookkeeping. Every engine resource (mesh, texture, shader,
 * render target) uploads lazily on first use and remembers the context
 * [generation] it was uploaded to. When Android destroys the EGL context,
 * the generation increments and every resource transparently re-uploads
 * from its CPU-side data — games never handle context loss themselves.
 */
object Gpu {
    @Volatile var generation = 0
        internal set
    /** True on the GL thread while a context is current. */
    @Volatile var ready = false
        internal set

    internal fun newContext() {
        generation++
        ready = true
    }

    fun floatBuffer(capacity: Int): FloatBuffer =
        ByteBuffer.allocateDirect(capacity * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun intBuffer(capacity: Int): IntBuffer =
        ByteBuffer.allocateDirect(capacity * 4).order(ByteOrder.nativeOrder()).asIntBuffer()

    fun shortBuffer(capacity: Int): ShortBuffer =
        ByteBuffer.allocateDirect(capacity * 2).order(ByteOrder.nativeOrder()).asShortBuffer()

    fun byteBuffer(capacity: Int): ByteBuffer = ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder())

    internal fun genBuffer(): Int { val a = IntArray(1); GLES30.glGenBuffers(1, a, 0); return a[0] }
    internal fun genVertexArray(): Int { val a = IntArray(1); GLES30.glGenVertexArrays(1, a, 0); return a[0] }
    internal fun genTexture(): Int { val a = IntArray(1); GLES30.glGenTextures(1, a, 0); return a[0] }
    internal fun genFramebuffer(): Int { val a = IntArray(1); GLES30.glGenFramebuffers(1, a, 0); return a[0] }
    internal fun genRenderbuffer(): Int { val a = IntArray(1); GLES30.glGenRenderbuffers(1, a, 0); return a[0] }

    internal fun deleteBuffer(id: Int) { if (id != 0) GLES30.glDeleteBuffers(1, intArrayOf(id), 0) }
    internal fun deleteVertexArray(id: Int) { if (id != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(id), 0) }
    internal fun deleteTexture(id: Int) { if (id != 0) GLES30.glDeleteTextures(1, intArrayOf(id), 0) }
    internal fun deleteFramebuffer(id: Int) { if (id != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(id), 0) }
    internal fun deleteRenderbuffer(id: Int) { if (id != 0) GLES30.glDeleteRenderbuffers(1, intArrayOf(id), 0) }

    /** Logs (does not throw) pending GL errors; used at a few checkpoints. */
    fun checkError(where: String): Boolean {
        var failed = false
        var guard = 0
        while (guard++ < 8) {
            val e = GLES30.glGetError()
            if (e == GLES30.GL_NO_ERROR) break
            Log.w("OeaGpu", "GL error 0x${Integer.toHexString(e)} at $where")
            failed = true
        }
        return failed
    }
}

/**
 * A GLSL ES 3.00 program. Compiles on first [bind]; uniform locations are
 * cached. A program that fails to compile logs the driver message once and
 * [bind] returns false, so a single bad shader never crashes the game.
 */
class Shader(val name: String, private val vertexSource: String, private val fragmentSource: String) {
    private var program = 0
    private var generation = -1
    private var failed = false
    private val locations = HashMap<String, Int>()

    val isReady get() = program != 0 && generation == Gpu.generation

    fun bind(): Boolean {
        if (generation != Gpu.generation) compile()
        if (program == 0) return false
        GLES30.glUseProgram(program)
        return true
    }

    fun loc(uniform: String): Int = locations.getOrPut(uniform) { GLES30.glGetUniformLocation(program, uniform) }

    fun set1i(u: String, v: Int) { val l = loc(u); if (l >= 0) GLES30.glUniform1i(l, v) }
    fun set1f(u: String, v: Float) { val l = loc(u); if (l >= 0) GLES30.glUniform1f(l, v) }
    fun set2f(u: String, a: Float, b: Float) { val l = loc(u); if (l >= 0) GLES30.glUniform2f(l, a, b) }
    fun set3f(u: String, a: Float, b: Float, c: Float) { val l = loc(u); if (l >= 0) GLES30.glUniform3f(l, a, b, c) }
    fun set4f(u: String, a: Float, b: Float, c: Float, d: Float) { val l = loc(u); if (l >= 0) GLES30.glUniform4f(l, a, b, c, d) }
    fun set3fv(u: String, v: FloatArray, offset: Int = 0) { val l = loc(u); if (l >= 0) GLES30.glUniform3fv(l, 1, v, offset) }
    fun set4fv(u: String, v: FloatArray, offset: Int = 0) { val l = loc(u); if (l >= 0) GLES30.glUniform4fv(l, 1, v, offset) }
    fun setMat4(u: String, m: FloatArray, offset: Int = 0) { val l = loc(u); if (l >= 0) GLES30.glUniformMatrix4fv(l, 1, false, m, offset) }

    private fun compile() {
        generation = Gpu.generation
        locations.clear()
        program = 0
        val vs = compileStage(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fs = compileStage(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        if (vs == 0 || fs == 0) {
            if (vs != 0) GLES30.glDeleteShader(vs)
            if (fs != 0) GLES30.glDeleteShader(fs)
            return
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, vs)
        GLES30.glAttachShader(p, fs)
        GLES30.glLinkProgram(p)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        val status = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            if (!failed) Log.e(TAG, "Link failed for $name: ${GLES30.glGetProgramInfoLog(p)}")
            failed = true
            GLES30.glDeleteProgram(p)
            return
        }
        program = p
    }

    private fun compileStage(type: Int, source: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, source)
        GLES30.glCompileShader(s)
        val status = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            if (!failed) Log.e(TAG, "Compile failed for $name (${if (type == GLES30.GL_VERTEX_SHADER) "vertex" else "fragment"}): ${GLES30.glGetShaderInfoLog(s)}")
            failed = true
            GLES30.glDeleteShader(s)
            return 0
        }
        return s
    }

    fun dispose() {
        if (program != 0 && generation == Gpu.generation) GLES30.glDeleteProgram(program)
        program = 0
        generation = -1
    }

    private companion object { const val TAG = "OeaShader" }
}
