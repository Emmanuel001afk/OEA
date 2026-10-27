package com.oea.engine.graphics

import android.opengl.GLES30
import com.oea.engine.math.Colors
import com.oea.engine.math.Frustum
import com.oea.engine.math.Mat4
import com.oea.engine.math.MathX
import com.oea.engine.math.Vec3
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/** Per-frame renderer counters (shown in the FPS overlay). */
class RenderStats {
    var drawCalls = 0
    var instances = 0
    var triangles = 0
    var culled = 0
    var shadowCasters = 0
    internal fun reset() { drawCalls = 0; instances = 0; triangles = 0; culled = 0; shadowCasters = 0 }
}

/**
 * Forward renderer with automatic instancing. Games call [draw] as often as
 * they like during a world pass; at the end of the pass the renderer culls
 * against the frustum, renders the sun's shadow map, groups identical
 * mesh+material pairs into single instanced draw calls, draws the sky into
 * the remaining pixels and finally blends transparent objects back to front.
 */
class Renderer3D internal constructor(private val graphics: Graphics) {
    val stats = RenderStats()

    private val std = Shader("standard", Shaders.STD_VERT, Shaders.STD_FRAG)
    private val depth = Shader("shadow-depth", Shaders.DEPTH_VERT, Shaders.DEPTH_FRAG)
    private val sky = Shader("sky", Shaders.SKY_VERT, Shaders.SKY_FRAG)
    private val skin = Shader("skin", Shaders.SKIN_VERT, Shaders.STD_FRAG)

    private var capacity = 512
    private var meshes = arrayOfNulls<Mesh>(capacity)
    private var materials = arrayOfNulls<Material>(capacity)
    private var matrices = FloatArray(capacity * 16)
    private var tints = FloatArray(capacity * 4)
    private var visible = BooleanArray(capacity)
    private var keys = LongArray(capacity)
    private var count = 0

    private val mainStream = InstanceStream(512)
    private val shadowStream = InstanceStream(256)
    private var runMesh = arrayOfNulls<Mesh>(256)
    private var runMaterial = arrayOfNulls<Material>(256)
    private var runStart = IntArray(256)
    private var runCount = IntArray(256)
    private var runs = 0

    // ---- skinned characters (non-batched; bone matrices ride in the vertex stream)
    private class SkinnedDraw {
        var mesh: SkeletalMesh? = null
        var material: Material? = null
        val transform = Mat4()
        var tint = Colors.WHITE
        var bones: FloatArray? = null
    }
    private var skinned = arrayOfNulls<SkinnedDraw>(64)
    private var skinnedCount = 0
    private var charCapsule: Mesh? = null
    private val whiteTint = FloatArray(4).also { Colors.toLinear(Colors.WHITE, it) }
    private val lin2 = FloatArray(4)
    private val capIndices = ArrayList<Int>()

    private val particleSystems = ArrayList<ParticleSystem>()

    private var camera: Camera? = null
    private var env = Environment()
    private val tmpMat = Mat4()
    private val lightView = Mat4()
    private val lightProj = Mat4()
    private val lightViewProj = Mat4()
    private val lightFrustum = Frustum()
    private val lin = FloatArray(4)
    private val v3 = Vec3()
    private val v3b = Vec3()
    private var shadowsThisPass = false
    private var shadowMap: ShadowMap? = null
    private var lastMaterial: Material? = null

    /** Shared material for [blobShadow]. */
    private val blobMaterial by lazy {
        Material(color = Colors.BLACK, texture = Textures.softCircle(64, 0.25f), unlit = true, transparent = true, depthWrite = false,
            castShadows = false, receiveShadows = false, fog = false)
    }

    // ---------------------------------------------------------------- public API

    /** Queues [mesh] with [material] at [transform]. [tint] multiplies the material colour. */
    fun draw(mesh: Mesh, material: Material, transform: Mat4, tint: Int = Colors.WHITE) {
        val i = alloc(mesh, material, tint)
        System.arraycopy(transform.m, 0, matrices, i * 16, 16)
    }

    /** Position + yaw + uniform scale. */
    fun draw(mesh: Mesh, material: Material, x: Float, y: Float, z: Float, yaw: Float = 0f, scale: Float = 1f, tint: Int = Colors.WHITE) {
        tmpMat.setPosYaw(x, y, z, yaw, scale)
        draw(mesh, material, tmpMat, tint)
    }

    /** Full transform: position, rotation (yaw/pitch/roll) and per-axis scale. */
    fun draw(mesh: Mesh, material: Material, x: Float, y: Float, z: Float, yaw: Float, pitch: Float, roll: Float, sx: Float, sy: Float, sz: Float, tint: Int = Colors.WHITE) {
        tmpMat.setTRS(x, y, z, yaw, pitch, roll, sx, sy, sz)
        draw(mesh, material, tmpMat, tint)
    }

    /**
     * Queues a vertex-skinned mesh (bones live in the vertex stream; the
     * skeleton's packed bone matrices are uploaded per draw). Draws are
     * non-batched — one call per part per character — because characters are
     * few and each part has its own material.
     */
    fun drawSkinned(mesh: SkeletalMesh, material: Material, bones: FloatArray, transform: Mat4, tint: Int = Colors.WHITE) {
        if (skinnedCount == skinned.size) skinned = skinned.copyOf(skinnedCount * 2)
        val i = skinnedCount++
        val d = skinned[i] ?: SkinnedDraw().also { skinned[i] = it }
        d.mesh = mesh
        d.material = material
        System.arraycopy(transform.m, 0, d.transform.m, 0, 16)
        d.tint = tint
        d.bones = bones
    }

    /** A solid tube between two points (unit cylinder stretched), e.g. tracers, edges of 4D shapes. */
    fun drawLine(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, radius: Float, material: Material, tint: Int = Colors.WHITE) {
        val dx = bx - ax; val dy = by - ay; val dz = bz - az
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-5f) return
        val yx = dx / len; val yy = dy / len; val yz = dz / len
        // Any vector not parallel to the axis.
        var rx = 0f; var ry = 0f; var rz = 1f
        if (abs(yz) > 0.9f) { rx = 1f; rz = 0f }
        var xx = yy * rz - yz * ry; var xy = yz * rx - yx * rz; var xz = yx * ry - yy * rx
        val xl = sqrt(xx * xx + xy * xy + xz * xz); xx /= xl; xy /= xl; xz /= xl
        val zx = xy * yz - xz * yy; val zy = xz * yx - xx * yz; val zz = xx * yy - xy * yx
        val d = radius * 2f
        val m = tmpMat.m
        m[0] = xx * d; m[1] = xy * d; m[2] = xz * d; m[3] = 0f
        m[4] = yx * len; m[5] = yy * len; m[6] = yz * len; m[7] = 0f
        m[8] = zx * d; m[9] = zy * d; m[10] = zz * d; m[11] = 0f
        m[12] = ax; m[13] = ay; m[14] = az; m[15] = 1f
        draw(Mesh.unitCylinder, material, tmpMat, tint)
    }

    /** Soft contact shadow on the ground (cheap; used when shadow maps are off, or for extra grounding). */
    fun blobShadow(x: Float, y: Float, z: Float, radius: Float, opacity: Float = 0.45f) {
        tmpMat.setTRS(x, y + 0.02f, z, sx = radius * 2f, sy = 1f, sz = radius * 2f)
        draw(Mesh.unitQuad, blobMaterial, tmpMat, Colors.withAlpha(Colors.WHITE, opacity))
    }

    /** Whether real shadow maps are active this pass (games may skip blob shadows then). */
    val shadowMapsActive: Boolean get() = shadowsThisPass

    /** Draws [system] with this pass (after transparent objects). */
    fun particles(system: ParticleSystem) { particleSystems += system }

    // ---------------------------------------------------------------- pass management

    internal fun begin(camera: Camera, environment: Environment) {
        this.camera = camera
        this.env = environment
        count = 0
        skinnedCount = 0
        particleSystems.clear()
    }

    private fun alloc(mesh: Mesh, material: Material, tint: Int): Int {
        if (count == capacity) grow()
        val i = count++
        meshes[i] = mesh
        materials[i] = material
        Colors.toLinear(tint, tints, i * 4)
        return i
    }

    private fun grow() {
        capacity *= 2
        meshes = meshes.copyOf(capacity)
        materials = materials.copyOf(capacity)
        matrices = matrices.copyOf(capacity * 16)
        tints = tints.copyOf(capacity * 4)
        visible = BooleanArray(capacity)
        keys = LongArray(capacity)
    }

    /** Renders everything queued since [begin]. Called by [Graphics.endWorld]. */
    internal fun flush() {
        val cam = camera ?: return
        val quality = graphics.quality
        cull(cam)
        shadowsThisPass = env.shadows && quality.shadows && quality.shadowMapSize > 0 && !graphics.perfThermalLimited() && count > 0
        if (shadowsThisPass) {
            val map = shadowMap?.takeIf { it.size == quality.shadowMapSize } ?: ShadowMap(quality.shadowMapSize).also { shadowMap?.dispose(); shadowMap = it }
            if (!renderShadows(cam, map)) shadowsThisPass = false
            graphics.rebindWorldTarget()
        }
        renderMain(cam)
        // Leave predictable state for the next pass / 2D.
        GLES30.glBindVertexArray(0)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        lastMaterial = null
        for (i in 0 until count) { meshes[i] = null; materials[i] = null }
        count = 0
        skinnedCount = 0
        camera = null
    }

    private fun cull(cam: Camera) {
        val f = cam.frustum
        val farLimit2 = cam.far * cam.far * 1.2f
        for (i in 0 until count) {
            val mesh = meshes[i]!!
            val o = i * 16
            val m = matrices
            val cx = m[o] * mesh.center.x + m[o + 4] * mesh.center.y + m[o + 8] * mesh.center.z + m[o + 12]
            val cy = m[o + 1] * mesh.center.x + m[o + 5] * mesh.center.y + m[o + 9] * mesh.center.z + m[o + 13]
            val cz = m[o + 2] * mesh.center.x + m[o + 6] * mesh.center.y + m[o + 10] * mesh.center.z + m[o + 14]
            val s = sqrt(maxOf(m[o] * m[o] + m[o + 1] * m[o + 1] + m[o + 2] * m[o + 2],
                m[o + 4] * m[o + 4] + m[o + 5] * m[o + 5] + m[o + 6] * m[o + 6],
                m[o + 8] * m[o + 8] + m[o + 9] * m[o + 9] + m[o + 10] * m[o + 10]))
            val r = mesh.radius * s
            val dx = cx - cam.position.x; val dy = cy - cam.position.y; val dz = cz - cam.position.z
            visible[i] = (dx * dx + dy * dy + dz * dz) < farLimit2 + r * r * 4f && f.sphereVisible(cx, cy, cz, r)
            if (!visible[i]) stats.culled++
        }
    }

    // ---------------------------------------------------------------- shadows

    private fun renderShadows(cam: Camera, map: ShadowMap): Boolean {
        val r = env.shadowDistance
        // Focus the shadow map ahead of the camera, around its target.
        val fx = cam.target.x; val fy = cam.target.y; val fz = cam.target.z
        val sd = env.sunDirection
        val upY = if (abs(sd.y) > 0.98f) 0f else 1f
        val upZ = if (abs(sd.y) > 0.98f) 1f else 0f
        lightView.lookAt(fx + sd.x * r * 2f, fy + sd.y * r * 2f, fz + sd.z * r * 2f, fx, fy, fz, 0f, upY, upZ)
        // Snap to whole texels so shadows don't shimmer as the camera moves.
        val texel = 2f * r / map.size
        val lm = lightView.m
        val lx = lm[0] * fx + lm[4] * fy + lm[8] * fz + lm[12]
        val ly = lm[1] * fx + lm[5] * fy + lm[9] * fz + lm[13]
        lm[12] -= lx - floor(lx / texel) * texel
        lm[13] -= ly - floor(ly / texel) * texel
        lightProj.ortho(-r, r, -r, r, 0.1f, r * 4.5f)
        lightViewProj.mul(lightProj, lightView)
        lightFrustum.update(lightViewProj)

        // Casters: everything with castShadows, culled against the light frustum (not the camera's).
        var n = 0
        for (i in 0 until count) {
            val mat = materials[i]!!
            if (!mat.castShadows || mat.transparent) continue
            val mesh = meshes[i]!!
            val o = i * 16
            val m = matrices
            val cx = m[o] * mesh.center.x + m[o + 4] * mesh.center.y + m[o + 8] * mesh.center.z + m[o + 12]
            val cy = m[o + 1] * mesh.center.x + m[o + 5] * mesh.center.y + m[o + 9] * mesh.center.z + m[o + 13]
            val cz = m[o + 2] * mesh.center.x + m[o + 6] * mesh.center.y + m[o + 10] * mesh.center.z + m[o + 14]
            val s = sqrt(maxOf(m[o] * m[o] + m[o + 1] * m[o + 1] + m[o + 2] * m[o + 2],
                m[o + 4] * m[o + 4] + m[o + 5] * m[o + 5] + m[o + 6] * m[o + 6],
                m[o + 8] * m[o + 8] + m[o + 9] * m[o + 9] + m[o + 10] * m[o + 10]))
            if (!lightFrustum.sphereVisible(cx, cy, cz, mesh.radius * s)) continue
            keys[n++] = (mesh.id.toLong() shl 20) or i.toLong()
        }
        if (n == 0) return false
        java.util.Arrays.sort(keys, 0, n)
        shadowStream.reset()
        runs = 0
        var prevMesh: Mesh? = null
        for (k in 0 until n) {
            val i = (keys[k] and 0xFFFFF).toInt()
            val mesh = meshes[i]!!
            if (mesh !== prevMesh) {
                addRun(mesh, null, shadowStream.count)
                prevMesh = mesh
            }
            shadowStream.add(matrices, i * 16, tints, i * 4)
            runCount[runs - 1]++
        }
        // Skinned characters cast a capsule shadow: a rigid capsule instance per
        // character (the depth shader needs no bones). Same capsule for everyone.
        var capsuleMesh = charCapsule
        if (capsuleMesh == null) {
            capsuleMesh = MeshBuilder().capsule(0f, 0f, 0f, 0.42f, 1.7f, 12).build()
            charCapsule = capsuleMesh
        }
        capIndices.clear()
        for (k in 0 until skinnedCount) {
            val d = skinned[k]!!
            val mat = d.material ?: continue
            if (!mat.castShadows || mat.transparent) continue
            val t = d.transform.m
            if (!lightFrustum.sphereVisible(t[12], t[13], t[14], 1.1f)) continue
            capIndices += k
        }
        if (capIndices.isNotEmpty()) {
            addRun(capsuleMesh, null, shadowStream.count)
            for (k in capIndices) {
                shadowStream.add(skinned[k]!!.transform.m, 0, whiteTint, 0)
            }
            runCount[runs - 1] = capIndices.size
        }
        if (!map.begin()) return false
        if (!depth.bind()) { map.end(); return false }
        depth.setMat4("u_lightViewProj", lightViewProj.m)
        val vbo = shadowStream.upload()
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(1.6f, 4f)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        for (rI in 0 until runs) {
            val mesh = runMesh[rI]!!
            if (!mesh.bind()) continue
            if (mesh is Mesh) {
                mesh.bindInstances(vbo, runStart[rI] * Mesh.INSTANCE_BYTES)
                mesh.drawInstanced(runCount[rI])
            }
            stats.drawCalls++
        }
        stats.shadowCasters += n
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        map.end()
        return true
    }

    private fun addRun(mesh: Mesh, material: Material?, start: Int) {
        if (runs == runMesh.size) {
            val n = runs * 2
            runMesh = runMesh.copyOf(n); runMaterial = runMaterial.copyOf(n)
            runStart = runStart.copyOf(n); runCount = runCount.copyOf(n)
        }
        runMesh[runs] = mesh
        runMaterial[runs] = material
        runStart[runs] = start
        runCount[runs] = 0
        runs++
    }

    // ---------------------------------------------------------------- main pass

    private fun renderMain(cam: Camera) {
        mainStream.reset()
        runs = 0
        // Opaque: group by material then mesh.
        var n = 0
        for (i in 0 until count) {
            if (!visible[i] || materials[i]!!.transparent) continue
            keys[n++] = (materials[i]!!.id.toLong() shl 40) or (meshes[i]!!.id.toLong() shl 20) or i.toLong()
        }
        java.util.Arrays.sort(keys, 0, n)
        buildRuns(n)
        val opaqueRuns = runs
        // Transparent: far to near.
        n = 0
        for (i in 0 until count) {
            if (!visible[i] || !materials[i]!!.transparent) continue
            val o = i * 16
            val dx = matrices[o + 12] - cam.position.x; val dy = matrices[o + 13] - cam.position.y; val dz = matrices[o + 14] - cam.position.z
            val dq = MathX.clamp(((dx * dx + dy * dy + dz * dz) * 4f).toInt(), 0, 0x7FFFFF)
            keys[n++] = ((0x7FFFFF - dq).toLong() shl 20) or i.toLong()
        }
        java.util.Arrays.sort(keys, 0, n)
        buildRuns(n)

        val vbo = mainStream.upload()
        if (!std.bind()) { skinnedCount = 0; return }
        setPassUniforms(cam)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)
        for (r in 0 until opaqueRuns) drawRun(r, vbo)
        // Skinned characters: non-batched draws after the batched opaque runs.
        if (skinnedCount > 0) {
            if (skin.bind()) {
                setPassUniforms(skin)
                GLES30.glEnable(GLES30.GL_DEPTH_TEST)
                GLES30.glDepthFunc(GLES30.GL_LEQUAL)
                GLES30.glEnable(GLES30.GL_CULL_FACE)
                GLES30.glCullFace(GLES30.GL_BACK)
                for (k in 0 until skinnedCount) {
                    val d = skinned[k]!!
                    val mesh = d.mesh ?: continue
                    val t = d.transform.m
                    val cc = mesh.center
                    val cx = t[0] * cc.x + t[4] * cc.y + t[8] * cc.z + t[12]
                    val cy = t[1] * cc.x + t[5] * cc.y + t[9] * cc.z + t[13]
                    val cz = t[2] * cc.x + t[6] * cc.y + t[10] * cc.z + t[14]
                    val dx = cx - cam.position.x; val dy = cy - cam.position.y; val dz = cz - cam.position.z
                    val s = sqrt(maxOf(t[0] * t[0] + t[1] * t[1] + t[2] * t[2],
                        t[4] * t[4] + t[5] * t[5] + t[6] * t[6],
                        t[8] * t[8] + t[9] * t[9] + t[10] * t[10]))
                    val r = mesh.boundingRadius * s
                    if ((dx * dx + dy * dy + dz * dz) > cam.far * cam.far * 1.2f + r * r * 4f || !cam.frustum.sphereVisible(cx, cy, cz, r)) continue
                    if (!mesh.bind()) continue
                    val m = d.material ?: continue
                    if (m !== lastMaterial) { applyMaterial(skin, m); lastMaterial = m }
                    Colors.toLinear(d.tint, lin2)
                    skin.set4f("u_baseColor", lin[0] * lin2[0], lin[1] * lin2[1], lin[2] * lin2[2], lin[3] * lin2[3])
                    val bones = d.bones ?: continue
                    skin.setMat4("u_model", t)
                    skin.setMat4n("u_bones", bones.size / 16, bones)
                    mesh.draw()
                    stats.drawCalls++
                    stats.triangles += mesh.indexCount / 3
                }
            }
        }
        if (env.drawSky) drawSky(cam)
        if (runs > opaqueRuns) {
            std.bind()
            lastMaterial = null
            for (r in opaqueRuns until runs) drawRun(r, vbo)
        }
        if (particleSystems.isNotEmpty()) {
            for (ps in particleSystems) ps.render(cam, this)
            particleSystems.clear()
        }
    }

    private fun buildRuns(n: Int) {
        var prevMesh: Mesh? = null
        var prevMat: Material? = null
        for (k in 0 until n) {
            val i = (keys[k] and 0xFFFFF).toInt()
            val mesh = meshes[i]!!
            val mat = materials[i]!!
            if (mesh !== prevMesh || mat !== prevMat) {
                addRun(mesh, mat, mainStream.count)
                prevMesh = mesh; prevMat = mat
            }
            mainStream.add(matrices, i * 16, tints, i * 4)
            runCount[runs - 1]++
        }
    }

    private fun drawRun(r: Int, vbo: Int) {
        val mesh = runMesh[r]!!
        val mat = runMaterial[r]!!
        if (mat !== lastMaterial) { applyMaterial(std, mat); lastMaterial = mat }
        if (!mesh.bind()) return
        mesh.bindInstances(vbo, runStart[r] * Mesh.INSTANCE_BYTES)
        mesh.drawInstanced(runCount[r])
        stats.drawCalls++
        stats.instances += runCount[r]
        stats.triangles += (if (mesh.indexCount > 0) mesh.indexCount else mesh.vertexCount) / 3 * runCount[r]
    }

    private fun setPassUniforms(cam: Camera, s: Shader = std) {
        s.setMat4("u_viewProj", cam.viewProj.m)
        s.setMat4("u_lightViewProj", lightViewProj.m)
        s.set3f("u_camPos", cam.position.x, cam.position.y, cam.position.z)
        val sd = env.sunDirection
        s.set3f("u_sunDir", sd.x, sd.y, sd.z)
        Colors.toLinear(env.sunColor, lin)
        s.set3f("u_sunColor", lin[0] * env.sunIntensity, lin[1] * env.sunIntensity, lin[2] * env.sunIntensity)
        Colors.toLinear(env.skyAmbient, lin)
        s.set3f("u_skyAmbient", lin[0] * env.ambientIntensity, lin[1] * env.ambientIntensity, lin[2] * env.ambientIntensity)
        Colors.toLinear(env.groundAmbient, lin)
        s.set3f("u_groundAmbient", lin[0] * env.ambientIntensity, lin[1] * env.ambientIntensity, lin[2] * env.ambientIntensity)
        Colors.toLinear(env.fogColor, lin)
        s.set4f("u_fog", lin[0], lin[1], lin[2], env.fogDensity)
        s.set1f("u_exposure", env.exposure)
        s.set1i("u_tex", 0)
        s.set1i("u_shadowMap", 1)
        uploadPointLights(s, cam)
        val map = shadowMap
        if (shadowsThisPass && map != null) {
            map.bindTexture(1)
            s.set4f("u_shadowParams", 1f, 1f / map.size, 0.0012f, env.shadowStrength)
        } else {
            ShadowMap.bindDummy(1)
            s.set4f("u_shadowParams", 0f, 0f, 0f, 0f)
        }
    }

    /** Strongest few point lights near the camera; the rest fade out silently. */
    private fun uploadPointLights(s: Shader, cam: Camera) {
        val lights = env.pointLights
        val n = lights.size
        if (n == 0) {
            for (i in 0 until 8) s.set4f("u_pointColor[$i]", 0f, 0f, 0f, 0f)
            return
        }
        // Importance = intensity / (1 + distance²/radius²); sort the few we keep.
        if (n > 1) {
            val cx = cam.position.x; val cy = cam.position.y; val cz = cam.position.z
            lights.sortWith { a, b ->
                val da = (a.x - cx) * (a.x - cx) + (a.y - cy) * (a.y - cy) + (a.z - cz) * (a.z - cz)
                val db = (b.x - cx) * (b.x - cx) + (b.y - cy) * (b.y - cy) + (b.z - cz) * (b.z - cz)
                val ia = a.intensity / (1f + da / (a.radius * a.radius + 1e-3f))
                val ib = b.intensity / (1f + db / (b.radius * b.radius + 1e-3f))
                if (ib > ia) 1 else if (ib < ia) -1 else 0
            }
        }
        var i = 0
        while (i < 8 && i < n) {
            val l = lights[i]
            s.set4f("u_pointPos[$i]", l.x, l.y, l.z, l.radius)
            Colors.toLinear(l.color, lin)
            s.set4f("u_pointColor[$i]", lin[0] * l.intensity, lin[1] * l.intensity, lin[2] * l.intensity, 1f)
            i++
        }
        while (i < 8) {
            s.set4f("u_pointColor[$i]", 0f, 0f, 0f, 0f)
            i++
        }
    }

    private fun applyMaterial(s: Shader, m: Material) {
        Colors.toLinear(m.color, lin)
        s.set4f("u_baseColor", lin[0], lin[1], lin[2], lin[3])
        Colors.toLinear(m.emissive, lin)
        val es = if (m.emissive == Colors.BLACK) 0f else m.emissiveStrength
        s.set3f("u_emissive", lin[0] * es, lin[1] * es, lin[2] * es)
        s.set4f("u_params", m.specular, m.shininess, m.rim, if (m.unlit) 1f else 0f)
        val tex = m.texture
        s.set4f("u_flags", if (tex != null) 1f else 0f, if (m.receiveShadows) 1f else 0f, if (m.fog) 1f else 0f, m.alphaCutoff)
        s.set4f("u_uvTransform", m.uvScaleX, m.uvScaleY, m.uvOffsetX, m.uvOffsetY)
        (tex ?: Textures.white).bind(0)
        if (m.transparent || m.additive) {
            GLES30.glEnable(GLES30.GL_BLEND)
            if (m.additive) GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
            else GLES30.glBlendFuncSeparate(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        } else {
            GLES30.glDisable(GLES30.GL_BLEND)
        }
        GLES30.glDepthMask(m.depthWrite && !m.additive)
        if (m.doubleSided) GLES30.glDisable(GLES30.GL_CULL_FACE) else GLES30.glEnable(GLES30.GL_CULL_FACE)
    }

    private fun drawSky(cam: Camera) {
        if (!sky.bind()) return
        sky.setMat4("u_invViewProj", cam.invViewProj.m)
        sky.set3f("u_camPos", cam.position.x, cam.position.y, cam.position.z)
        Colors.toLinear(env.skyTop, lin); sky.set3f("u_skyTop", lin[0], lin[1], lin[2])
        Colors.toLinear(env.skyHorizon, lin); sky.set3f("u_skyHorizon", lin[0], lin[1], lin[2])
        Colors.toLinear(env.skyGround, lin); sky.set3f("u_ground", lin[0], lin[1], lin[2])
        sky.set3f("u_sunDir", env.sunDirection.x, env.sunDirection.y, env.sunDirection.z)
        Colors.toLinear(env.sunColor, lin); sky.set3f("u_sunColor", lin[0] * env.sunIntensity * 0.5f, lin[1] * env.sunIntensity * 0.5f, lin[2] * env.sunIntensity * 0.5f)
        sky.set1f("u_sunSize", env.sunSize)
        sky.set1f("u_exposure", env.exposure)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        FullscreenTriangle.draw()
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        stats.drawCalls++
    }

    internal fun resetStats() = stats.reset()

    @Suppress("unused") private fun unused() = v3.add(v3b)
}

/** Depth-texture shadow map for the sun. */
internal class ShadowMap(val size: Int) {
    private var fbo = 0
    private var tex = 0
    private var generation = -1
    private var failed = false

    fun begin(): Boolean {
        if (failed) return false
        if (generation != Gpu.generation || fbo == 0) create()
        if (fbo == 0) return false
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, size, size)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        return true
    }

    fun end() { GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0) }

    fun bindTexture(unit: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    private fun create() {
        generation = Gpu.generation
        tex = Gpu.genTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_DEPTH_COMPONENT24, size, size, 0, GLES30.GL_DEPTH_COMPONENT, GLES30.GL_UNSIGNED_INT, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_MODE, GLES30.GL_COMPARE_REF_TO_TEXTURE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_FUNC, GLES30.GL_LEQUAL)
        fbo = Gpu.genFramebuffer()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_TEXTURE_2D, tex, 0)
        GLES30.glDrawBuffers(1, intArrayOf(GLES30.GL_NONE), 0)
        GLES30.glReadBuffer(GLES30.GL_NONE)
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            android.util.Log.w("OeaShadow", "Shadow map unsupported (0x${Integer.toHexString(status)}); using blob shadows")
            Gpu.deleteFramebuffer(fbo); Gpu.deleteTexture(tex)
            fbo = 0; tex = 0
            failed = true
        }
    }

    fun dispose() {
        if (generation == Gpu.generation) { Gpu.deleteFramebuffer(fbo); Gpu.deleteTexture(tex) }
        fbo = 0; tex = 0
    }

    companion object {
        private var dummy = 0
        private var dummyGeneration = -1

        /** A 1×1 comparison texture so the sampler is always valid when shadows are off. */
        fun bindDummy(unit: Int) {
            if (dummyGeneration != Gpu.generation) {
                dummyGeneration = Gpu.generation
                dummy = Gpu.genTexture()
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dummy)
                val buf = Gpu.intBuffer(1).put(-1); buf.position(0)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_DEPTH_COMPONENT24, 1, 1, 0, GLES30.GL_DEPTH_COMPONENT, GLES30.GL_UNSIGNED_INT, buf)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_MODE, GLES30.GL_COMPARE_REF_TO_TEXTURE)
            }
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dummy)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        }
    }
}

/** A single triangle covering the screen (sky and post-processing). */
internal object FullscreenTriangle {
    private var vao = 0
    private var vbo = 0
    private var generation = -1

    fun draw() {
        if (generation != Gpu.generation || vao == 0) {
            generation = Gpu.generation
            vao = Gpu.genVertexArray()
            vbo = Gpu.genBuffer()
            GLES30.glBindVertexArray(vao)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            val fb = Gpu.floatBuffer(6).put(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f))
            fb.position(0)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 24, fb, GLES30.GL_STATIC_DRAW)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        } else {
            GLES30.glBindVertexArray(vao)
        }
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
    }
}
