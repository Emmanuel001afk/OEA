package com.oea.games.runner

import com.oea.engine.graphics.Material
import com.oea.engine.graphics.Mesh
import com.oea.engine.graphics.MeshBuilder
import com.oea.engine.graphics.Renderer3D
import com.oea.engine.graphics.Textures
import com.oea.engine.math.Colors
import com.oea.engine.math.Mat4
import com.oea.engine.math.MathX
import com.oea.engine.math.Vec2
import kotlin.math.cos
import kotlin.math.sin

/** Meshes and materials for the Runner, all procedural. */
internal object RunnerArt {
    // ---------------------------------------------------------------- materials
    val gravel by lazy { Material(color = 0xFF5A5148.toInt(), texture = Textures.noise(256, 40f, 21, 0xFF4C443C.toInt(), 0xFF6E655A.toInt()), specular = 0.05f, uvScaleX = 1f, uvScaleY = 8f) }
    val ground by lazy { Material(color = 0xFF3C4148.toInt(), texture = Textures.noise(256, 18f, 22, 0xFF353A40.toInt(), 0xFF454B52.toInt()), specular = 0.05f, uvScaleX = 4f, uvScaleY = 4f) }
    val rail = Material(color = 0xFFB8BEC6.toInt(), specular = 0.9f, shininess = 120f)
    val sleeper by lazy { Material(color = 0xFF5C4330.toInt(), texture = Textures.wood(128, 0xFF6B4E36.toInt(), 0xFF3F2C1E.toInt(), 31), specular = 0.1f) }
    val concrete by lazy { Material(color = 0xFFB0AFAB.toInt(), texture = Textures.noise(128, 10f, 41, 0xFFA3A29E.toInt(), 0xFFC4C3BF.toInt()), specular = 0.1f) }
    val hurdleRed = Material(color = 0xFFE5484D.toInt(), specular = 0.4f, shininess = 40f)
    val hurdleWhite = Material(color = 0xFFF2F2F2.toInt(), specular = 0.4f, shininess = 40f)
    val warning = Material(color = 0xFFFFC21A.toInt(), specular = 0.3f, emissive = 0xFFFFC21A.toInt(), emissiveStrength = 0.15f)
    val metalDark = Material(color = 0xFF2C3036.toInt(), specular = 0.6f, shininess = 60f)
    val coin = Material(color = 0xFFFFC83D.toInt(), specular = 1f, shininess = 90f, emissive = 0xFFFFB300.toInt(), emissiveStrength = 0.9f, rim = 0.6f)
    val lampGlow = Material.glow(0xFFFFD9A0.toInt(), 3.2f)
    val trainLight = Material.glow(0xFFFFF4C2.toInt(), 4f)
    val trainWindow = Material(color = 0xFF1E2A38.toInt(), specular = 1f, shininess = 120f, emissive = 0xFF2B5A80.toInt(), emissiveStrength = 0.25f)
    val trainColors = listOf(
        Material(color = 0xFF2F6FDE.toInt(), specular = 0.7f, shininess = 70f, rim = 0.2f),
        Material(color = 0xFFD8402F.toInt(), specular = 0.7f, shininess = 70f, rim = 0.2f),
        Material(color = 0xFFF2B33A.toInt(), specular = 0.7f, shininess = 70f, rim = 0.2f),
        Material(color = 0xFF2FA37A.toInt(), specular = 0.7f, shininess = 70f, rim = 0.2f),
    )
    val trainRoof = Material(color = 0xFF9AA3AE.toInt(), specular = 0.5f, shininess = 40f)
    val buildingMats by lazy {
        listOf(
            Material(color = 0xFFE8E4DC.toInt(), texture = Textures.windows(256, 0xFF3A4152.toInt(), 0xFFFFD27A.toInt(), 0xFF1C2230.toInt(), 3), emissive = 0xFF000000.toInt(), uvScaleX = 1f, uvScaleY = 2f),
            Material(color = 0xFFD9E3F0.toInt(), texture = Textures.windows(256, 0xFF2A3446.toInt(), 0xFFB9E2FF.toInt(), 0xFF161C28.toInt(), 7), uvScaleX = 1f, uvScaleY = 3f),
            Material(color = 0xFFF0D8C4.toInt(), texture = Textures.windows(256, 0xFF4A3530.toInt(), 0xFFFFC27A.toInt(), 0xFF22191A.toInt(), 13), uvScaleX = 1f, uvScaleY = 2f),
            Material(color = 0xFFCFD8D2.toInt(), texture = Textures.windows(256, 0xFF2E3B37.toInt(), 0xFFE8FFB9.toInt(), 0xFF18201E.toInt(), 19), uvScaleX = 1f, uvScaleY = 2.5f),
        )
    }
    val roofTop = Material(color = 0xFF2A2E35.toInt(), specular = 0.1f)
    val neonSigns = listOf(Material.glow(0xFFFF3D7F.toInt(), 3f), Material.glow(0xFF3DD9FF.toInt(), 3f), Material.glow(0xFFB46CFF.toInt(), 3f))

    // Power-ups
    val magnetRed = Material(color = 0xFFE53935.toInt(), emissive = 0xFFE53935.toInt(), emissiveStrength = 0.5f, specular = 0.8f)
    val jetpackMat = Material(color = 0xFFB0BEC5.toInt(), emissive = 0xFF64B5F6.toInt(), emissiveStrength = 0.6f, specular = 0.9f, shininess = 90f)
    val sneakerMat = Material(color = 0xFF66BB6A.toInt(), emissive = 0xFF66BB6A.toInt(), emissiveStrength = 0.6f, specular = 0.5f)
    val multiplierMat = Material(color = 0xFFAB47BC.toInt(), emissive = 0xFFCE93D8.toInt(), emissiveStrength = 0.8f, specular = 0.6f)
    val shieldMat = Material(color = 0x6649C6FF, emissive = 0xFF49C6FF.toInt(), emissiveStrength = 0.9f, transparent = true, depthWrite = false, castShadows = false)

    // Character
    val skinDark = Material(color = 0xFF22262C.toInt(), specular = 0.6f, shininess = 50f)
    val visor = Material(color = 0xFF0E1116.toInt(), specular = 1f, shininess = 140f, emissive = 0xFF39D0FF.toInt(), emissiveStrength = 1.4f)
    val sole = Material(color = 0xFFF5F5F5.toInt(), specular = 0.3f)

    private val bodyMats = HashMap<Int, Material>()
    fun bodyMaterial(color: Int): Material = bodyMats.getOrPut(color) { Material(color = color, specular = 0.55f, shininess = 55f, rim = 0.35f) }

    private val ghostMats = HashMap<Int, Material>()
    fun ghostMaterial(color: Int): Material = ghostMats.getOrPut(color) {
        Material(color = Colors.withAlpha(color, 0.55f), emissive = color, emissiveStrength = 0.35f, transparent = true, depthWrite = false, castShadows = false, rim = 0.8f)
    }

    // ---------------------------------------------------------------- meshes
    val box: Mesh get() = Mesh.unitCube
    val coinMesh: Mesh by lazy {
        MeshBuilder().withTransform(Mat4().setTRS(0f, 0f, 0f, pitch = MathX.HALF_PI)) { cylinder(0f, -0.05f, 0f, 0.38f, 0.1f, 28) }.build()
    }
    val torso: Mesh by lazy {
        MeshBuilder().capsule(0f, 0f, 0f, 0.27f, 0.72f, 18).build()
    }
    val head: Mesh by lazy { MeshBuilder().sphere(0f, 0f, 0f, 0.23f, 20, 14).build() }
    val limb: Mesh by lazy { MeshBuilder().capsule(0f, -0.62f, 0f, 0.09f, 0.62f, 10).build() }
    val shoe: Mesh by lazy { MeshBuilder().box(0f, 0.06f, 0.06f, 0.2f, 0.12f, 0.34f).build() }
    val trainBody: Mesh by lazy {
        // Unit-length car (along +Z) with a rounded front; scaled per train.
        val mb = MeshBuilder()
        mb.box(0f, 1.85f, 0.5f, 2.3f, 3.1f, 1f)
        mb.build()
    }
    val lampPost: Mesh by lazy {
        MeshBuilder().cylinder(0f, 0f, 0f, 0.09f, 5.2f, 10).box(0.45f, 5.2f, 0f, 1.0f, 0.12f, 0.16f).build()
    }
    val magnet: Mesh by lazy {
        val mb = MeshBuilder()
        mb.withTransform(Mat4().setTRS(0f, 0.1f, 0f, pitch = MathX.HALF_PI)) {
            // Half torus as the U.
            torus(0f, 0f, 0f, 0.28f, 0.1f, 20, 10)
        }
        mb.box(-0.28f, -0.2f, 0f, 0.2f, 0.4f, 0.2f)
        mb.box(0.28f, -0.2f, 0f, 0.2f, 0.4f, 0.2f)
        mb.build()
    }
    val jetpack: Mesh by lazy {
        MeshBuilder().cylinder(-0.17f, -0.35f, 0f, 0.15f, 0.7f, 14).cylinder(0.17f, -0.35f, 0f, 0.15f, 0.7f, 14).cone(-0.17f, 0.35f, 0f, 0.15f, 0.2f, 14).cone(0.17f, 0.35f, 0f, 0.15f, 0.2f, 14).build()
    }
    val sneaker: Mesh by lazy { MeshBuilder().box(0f, 0f, 0f, 0.36f, 0.24f, 0.62f).box(0f, 0.18f, -0.15f, 0.34f, 0.2f, 0.3f).build() }
    val star: Mesh by lazy {
        val pts = (0 until 10).map { i ->
            val a = MathX.TAU * i / 10f + MathX.HALF_PI
            val r = if (i % 2 == 0) 0.45f else 0.2f
            Vec2(cos(a) * r, sin(a) * r)
        }
        MeshBuilder().extrude(pts, 0.14f).build()
    }
    val bubble: Mesh by lazy { MeshBuilder().sphere(0f, 0f, 0f, 0.5f, 20, 14).build() }

    private val m = Mat4()

    /** Draws a box from min/max corners with [mat]. */
    fun boxAt(r: Renderer3D, mat: Material, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, tint: Int = Colors.WHITE) {
        m.setTRS((x0 + x1) / 2f, (y0 + y1) / 2f, (z0 + z1) / 2f, sx = x1 - x0, sy = y1 - y0, sz = z1 - z0)
        r.draw(Mesh.unitCube, mat, m, tint)
    }
}
