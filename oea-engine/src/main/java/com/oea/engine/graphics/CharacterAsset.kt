package com.oea.engine.graphics

import android.graphics.Color
import com.oea.engine.OeaEngine
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * A character imported from a Tripo3D GLB (or any other rigged GLB) and
 * converted offline into OEA's compact character format. Everything here is
 * already in OEA space: 19 bones, bind-relative matrices, and vertices in the
 * same stride [SkeletalMesh] uses. No network, no glTF parser and no API key
 * are needed at runtime.
 */
class CharacterAsset(
    /** 19×16 column-major inverse-bind matrices; feed to [Skeleton]. */
    val inverseBind: FloatArray,
    val parts: List<Part>,
    /** Optional baked clips, each frame containing 19×16 bind-relative matrices. */
    val clips: Map<String, FloatArray>,
) {
    class Part(
        val mesh: SkeletalMesh,
        val material: Material,
        /** Hair/helmet/vest detail can be hidden at LOD 2. */
        val detail: Boolean,
    )
}

/**
 * Reader for the `.oeac` format written by `tools/tripo/glb_to_oeac.py`.
 *
 * Layout (little endian): `OEAC`, u16 version, u16 boneCount, u16 meshCount,
 * u16 clipCount; then bones (i16 parent, 16 f32 inverse bind), meshes (name,
 * texture, u32 colour, u32 flags, u32 vertex/index counts, vertices, indices)
 * and clips (name, u32 frameCount, u32 boneCount, matrices).
 */
object CharacterAssetLoader {
    private const val VERSION = 1
    private val MAX_BONES = Skeleton.Bone.entries.size
    private const val MAX_MESHES = 64
    private const val MAX_VERTICES = 200_000
    private const val MAX_INDICES = 600_000
    private const val MAX_CLIPS = 32
    private const val MAX_FRAMES = 600

    /** Loads a bundled character from `assets/`; textures live under [textureDir]. */
    fun fromAssets(path: String, textureDir: String): CharacterAsset {
        val stream = OeaEngine.context.assets.open(path.trimStart('/'))
        return stream.use { load(it) { name -> Texture.asset(join(textureDir, name)) } }
    }

    /** Loads a character from a game-private file; textures sit in a folder named after it. */
    fun fromFile(file: File): CharacterAsset = file.inputStream().use { stream ->
        val dir = File(file.parentFile, file.nameWithoutExtension)
        load(stream) { name -> Texture.file(File(dir, name).path) }
    }

    private fun join(dir: String, name: String): String =
        dir.trim('/').replace('\\', '/') + "/" + name.trimStart('/').replace('\\', '/')

    private inline fun load(stream: InputStream, textureFor: (String) -> Texture?): CharacterAsset {
        val r = Reader(stream.readBytes())
        if (String(r.bytes(4), StandardCharsets.US_ASCII) != "OEAC") error("Not an OEA character asset")
        if (r.u16() != VERSION) error("Unsupported .oeac version")
        val boneCount = r.u16()
        val meshCount = r.u16()
        val clipCount = r.u16()
        if (boneCount != MAX_BONES) error("Expected $MAX_BONES bones, got $boneCount")
        if (meshCount > MAX_MESHES || clipCount > MAX_CLIPS) error("Character asset exceeds mesh/clip limits")

        val inverseBind = FloatArray(MAX_BONES * 16)
        for (i in 0 until MAX_BONES) {
            r.i16() // parent — the hierarchy is fixed in Skeleton; kept for tooling
            r.floats(16).copyInto(inverseBind, i * 16)
        }
        val parts = ArrayList<CharacterAsset.Part>(meshCount)
        repeat(meshCount) {
            val name = r.string()
            val texture = r.string()
            val argb = r.u32()
            val color = Color.argb(
                (argb ushr 24) and 0xFF,
                (argb shr 16) and 0xFF,
                (argb shr 8) and 0xFF,
                argb and 0xFF,
            )
            val flags = r.u32()
            val verts = r.u32()
            val indices = r.u32()
            if (verts > MAX_VERTICES || indices > MAX_INDICES) error("Character part $name is too large")
            val vertices = r.floats(verts * SkeletalMesh.STRIDE)
            val idx = IntArray(indices) { r.u32() }
            val mat = Material(
                color = color,
                texture = if (texture.isEmpty()) null else textureFor(texture),
                specular = 0.24f,
                shininess = 36f,
                rim = 0.12f,
                doubleSided = flags and 4 != 0,
                subsurface = if (flags and 1 != 0) color else 0,
            )
            parts += CharacterAsset.Part(SkeletalMesh(vertices, idx), mat, flags and 2 != 0)
        }
        val clips = LinkedHashMap<String, FloatArray>(clipCount)
        repeat(clipCount) {
            val name = r.string()
            val frames = r.u32()
            val bones = r.u32()
            if (bones != MAX_BONES || frames > MAX_FRAMES) error("Bad clip $name in character asset")
            clips[name] = r.floats(frames * MAX_BONES * 16)
        }
        return CharacterAsset(inverseBind, parts, clips)
    }

    private class Reader(bytes: ByteArray) {
        private val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        private fun need(n: Int) {
            if (n < 0 || buf.remaining() < n) error("Truncated character asset")
        }

        fun bytes(n: Int): ByteArray { need(n); val out = ByteArray(n); buf.get(out); return out }
        fun u8(): Int { need(1); return buf.get().toInt() and 0xFF }
        fun i16(): Int { need(2); return buf.short.toInt() }
        fun u16(): Int { need(2); return buf.short.toInt() and 0xFFFF }
        fun u32(): Int { need(4); return buf.int }
        fun f32(): Float { need(4); return buf.float }
        fun floats(n: Int): FloatArray {
            if (n < 0) error("Bad float count")
            need(n * 4)
            val out = FloatArray(n)
            for (i in 0 until n) out[i] = buf.float
            return out
        }
        fun string(): String = String(bytes(u8()), StandardCharsets.UTF_8)
    }
}
