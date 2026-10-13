package com.oea.games.lab

import com.oea.engine.core.GameDescriptor
import com.oea.engine.core.GameDimension
import com.oea.engine.core.GameOrientation
import com.oea.engine.core.GameResult
import com.oea.engine.core.OeaGame
import com.oea.engine.core.PlayMode
import com.oea.engine.ecs.Lifetime
import com.oea.engine.ecs.LifetimeSystem
import com.oea.engine.ecs.MeshRenderer
import com.oea.engine.ecs.MovementSystem
import com.oea.engine.ecs.RenderSystem
import com.oea.engine.ecs.Transform
import com.oea.engine.ecs.Velocity
import com.oea.engine.ecs.World
import com.oea.engine.graphics.Align
import com.oea.engine.graphics.Camera2D
import com.oea.engine.graphics.Environment
import com.oea.engine.graphics.Frame
import com.oea.engine.graphics.Material
import com.oea.engine.graphics.Mesh
import com.oea.engine.graphics.MeshBuilder
import com.oea.engine.graphics.ParticlePreset
import com.oea.engine.graphics.ParticleSystem
import com.oea.engine.graphics.PerspectiveCamera
import com.oea.engine.graphics.Textures
import com.oea.engine.graphics.ui
import com.oea.engine.graphics.world
import com.oea.engine.graphics.world2D
import com.oea.engine.island.IslandStyle
import com.oea.engine.island.LiveActivity
import com.oea.engine.math.Colors
import com.oea.engine.math.MathX
import com.oea.engine.math.Rng
import com.oea.engine.math.Vec3
import com.oea.engine.math.nd.HyperMesh
import com.oea.engine.math.nd.HyperProjector
import com.oea.engine.math.nd.HyperTransform
import com.oea.engine.math.nd.MatN
import com.oea.engine.math.nd.Polytopes
import com.oea.engine.ui.ButtonStyle
import kotlin.math.cos
import kotlin.math.sin

/**
 * Engine Lab: one screen per capability, written only with the public engine
 * API, as a reference for new games. 2D (sprite world + ECS), 3D (materials,
 * shadows, particles) and N-dimensional geometry (4D/5D/6D polytopes
 * rotating in any plane and projected to 3D).
 */
class EngineLab : OeaGame() {
    private var page = 0
    private val pages = listOf("2D", "3D", "4D+")

    // 2D
    private val cam2d = Camera2D(20f)
    private val dots = Array(160) { FloatArray(6) }
    private val rng = Rng(42)

    // 3D
    private val cam3d = PerspectiveCamera(50f)
    private val env = Environment.sunset().apply { shadowDistance = 16f; fogDensity = 0.01f }
    private val world = World().addSystem(MovementSystem()).addSystem(LifetimeSystem())
    private val fountain = ParticleSystem(1500, Textures.softCircle(64, 0.15f), additive = true)
    private val floorMesh: Mesh by lazy { MeshBuilder().plane(0f, 0f, 0f, 40f, 40f, 1, 1, uvPerUnit = 0.5f).build() }
    private val torus: Mesh by lazy { MeshBuilder().torus(0f, 0f, 0f, 1.1f, 0.38f, 48, 20).build() }
    private val floorMat by lazy { Material(texture = Textures.checker(256, 8, 0xFF3A404A.toInt(), 0xFF2A2F37.toInt()), specular = 0.2f, uvScaleX = 1f, uvScaleY = 1f) }
    private val mats = listOf(
        Material(color = 0xFFE53935.toInt(), specular = 0.9f, shininess = 120f, rim = 0.3f),
        Material(color = 0xFF43A047.toInt(), specular = 0.2f, shininess = 10f),
        Material(color = 0xFF1E88E5.toInt(), emissive = 0xFF1E88E5.toInt(), emissiveStrength = 1.8f),
        Material(color = 0xFFFFD54F.toInt(), specular = 1f, shininess = 200f, rim = 0.6f),
        Material(color = 0x8880DEEA.toInt(), transparent = true, specular = 1f, shininess = 120f, castShadows = false),
    )
    private var spawnTimer = 0f

    // N-D
    private var dim = 4
    private var shape = 0
    private lateinit var hyper: HyperMesh
    private val transform = HyperTransform(6)
    private var rotation = MatN(4)
    private val projector = HyperProjector(3.2f)
    private val projected = ArrayList<Vec3>()
    private val depth = ArrayList<Float>()
    private var autoRotate = true
    private var plane = 0
    private val edgeMat = Material(color = Colors.WHITE, emissive = Colors.WHITE, emissiveStrength = 0.8f, specular = 0.6f)
    private val vertMat = Material(color = Colors.WHITE, emissive = Colors.WHITE, emissiveStrength = 1.2f)

    override fun onCreate() {
        for (d in dots) { d[0] = rng.range(-10f, 10f); d[1] = rng.range(-10f, 10f); d[2] = rng.range(-3f, 3f); d[3] = rng.range(-3f, 3f); d[4] = rng.range(0.15f, 0.45f); d[5] = rng.range(0f, 360f) }
        buildHyper()
    }

    private fun buildHyper() {
        hyper = when (shape) {
            0 -> Polytopes.hypercube(dim, 1.6f)
            1 -> Polytopes.crossPolytope(dim, 1.3f)
            else -> Polytopes.simplex(dim, 2.2f)
        }
        rotation = MatN(dim)
        plane = 0
    }

    override fun onUpdate(dt: Float) {
        val input = ctx.input
        when (page) {
            0 -> for (d in dots) {
                d[0] += d[2] * dt; d[1] += d[3] * dt
                val h = cam2d.viewHeight / 2f; val w = h * ctx.graphics.width / ctx.graphics.height
                if (d[0] < -w || d[0] > w) d[2] = -d[2]
                if (d[1] < -h || d[1] > h) d[3] = -d[3]
            }
            1 -> {
                world.update(dt)
                fountain.update(dt)
                fountain.burst(ParticlePreset.SPARKS, 0f, 0.3f, 0f, 6, 0f, 6f, 0f, 0xFF57C8FF.toInt())
                spawnTimer -= dt
                if (spawnTimer <= 0f) {
                    spawnTimer = 0.25f
                    val e = world.create()
                    world.add(e, Transform(Vec3(rng.range(-4f, 4f), 6f, rng.range(-4f, 4f)), scale = Vec3(0.4f, 0.4f, 0.4f)))
                    world.add(e, Velocity(Vec3(0f, 0f, 0f), yawRate = rng.range(-3f, 3f), gravity = -6f))
                    world.add(e, MeshRenderer(Mesh.unitCube, mats[rng.nextInt(mats.size)]))
                    world.add(e, Lifetime(2.2f))
                }
                val t = ctx.time.elapsed.toFloat()
                cam3d.target.set(0f, 1f, 0f)
                cam3d.orbit(t * 0.25f, 0.42f, 11f)
            }
            else -> {
                val nPlanes = MatN.planeCount(dim)
                for (p in input.pointers) if (p.isDown && p.isFree) {
                    val (a, b) = MatN.plane(dim, plane % nPlanes)
                    rotation.rotatePlane(a, b, -p.dx * 0.006f)
                    val (c, e) = MatN.plane(dim, (plane + 1) % nPlanes)
                    rotation.rotatePlane(c, e, p.dy * 0.006f)
                }
                if (autoRotate) {
                    // Rotate through every plane that involves a hidden dimension.
                    for (i in 0 until nPlanes) {
                        val (a, b) = MatN.plane(dim, i)
                        if (b >= 3) rotation.rotatePlane(a, b, dt * (0.25f + 0.1f * (i % 3)))
                    }
                    rotation.rotatePlane(0, 2, dt * 0.15f)
                }
                if (ctx.time.frame % 120L == 0L) rotation.orthonormalize()
                projected.clear(); depth.clear()
                val tmpIn = FloatArray(dim); val tmpOut = FloatArray(dim)
                for (v in hyper.vertices) {
                    v.copyInto(tmpIn)
                    rotation.transform(tmpIn, tmpOut)
                    val p = Vec3()
                    depth += projector.project(tmpOut, p)
                    projected += p.scl(1.6f).add(0f, 2.4f, 0f)
                }
                cam3d.target.set(0f, 2.4f, 0f)
                cam3d.orbit(0.6f, 0.25f, 10f)
            }
        }
        ctx.island.update(LiveActivity("Engine Lab", "Exploring ${pages[page]}", compactText = "Lab · ${pages[page]}", trailing = "${ctx.perf.stats.fps.toInt()} fps", preferred = IslandStyle.CAMERA))
    }

    override fun onRender(frame: Frame) {
        frame.clear(0xFF0E1116.toInt())
        when (page) {
            0 -> frame.world2D(cam2d) { b ->
                for (d in dots) b.drawRotated(Textures.white, d[0], d[1], d[4] * 2f, d[4] * 2f, d[5] * MathX.DEG_TO_RAD + ctx.time.elapsed.toFloat(), Colors.hsv(d[5], 0.65f, 1f))
                for (d in dots) b.glow(d[0], d[1], d[4] * 2.5f, Colors.withAlpha(Colors.hsv(d[5], 0.6f, 1f), 0.25f))
            }
            1 -> frame.world(cam3d, environment = env) { r ->
                r.draw(floorMesh, floorMat, 0f, 0f, 0f)
                val t = ctx.time.elapsed.toFloat()
                r.draw(Mesh.unitSphere, mats[0], -3f, 1f, 0f, t, 1.6f)
                r.draw(torus, mats[3], 0f, 1.4f, -2.5f, t, sin(t) * 0.6f, 0f, 1f, 1f, 1f)
                r.draw(Mesh.unitCube, mats[1], 3f, 0.8f, 0f, t * 0.7f, 1.4f)
                r.draw(Mesh.unitCylinder, mats[2], 0f, 0f, 2.6f, 0f, 0.9f)
                r.draw(Mesh.unitSphere, mats[4], 0f, 1.2f, 0.5f, 0f, 1.5f)
                RenderSystem.render(world, r)
                r.particles(fountain)
                if (!r.shadowMapsActive) { r.blobShadow(-3f, 0f, 0f, 0.9f); r.blobShadow(3f, 0f, 0f, 0.9f) }
            }
            else -> frame.world(cam3d, environment = Environment.night()) { r ->
                for (e in hyper.edges) {
                    val a = projected[e[0]]; val b = projected[e[1]]
                    val k = MathX.clamp01((depth[e[0]] + depth[e[1]]) / 2f / 1.6f + 0.5f)
                    r.drawLine(a.x, a.y, a.z, b.x, b.y, b.z, 0.035f, edgeMat, Colors.hsv(200f + 140f * k, 0.75f, 1f))
                }
                projected.forEachIndexed { i, p ->
                    val k = MathX.clamp01(depth[i] / 1.6f + 0.5f)
                    r.draw(Mesh.unitSphere, vertMat, p.x, p.y, p.z, 0f, 0.14f + 0.08f * k, Colors.hsv(200f + 140f * k, 0.6f, 1f))
                }
            }
        }
        frame.ui { hud ->
            val d = hud.density
            hud.accent = ACCENT
            val top = hud.safeTop + 12f * d
            hud.text("OEA Engine Lab", hud.width / 2f, top, 20f, Colors.WHITE, Align.CENTER, bold = true)
            val subtitle = when (page) {
                0 -> "2D: sprite world camera, batching, glows"
                1 -> "3D: materials, shadows, bloom, particles, ECS"
                else -> "${dim}D ${listOf("hypercube", "cross-polytope", "simplex")[shape]}: ${hyper.vertexCount} vertices, ${hyper.edges.size} edges, ${MatN.planeCount(dim)} rotation planes"
            }
            hud.text(subtitle, hud.width / 2f, top + 30f * d, 13f, 0xCCFFFFFF.toInt(), Align.CENTER)
            val bw = (hud.width - 48f * d) / 3f
            val by = hud.height - hud.safeBottom - 64f * d
            pages.forEachIndexed { i, p ->
                if (hud.button("page$i", p, 16f * d + i * (bw + 8f * d), by, bw, 48f * d, if (i == page) ButtonStyle.PRIMARY else ButtonStyle.SECONDARY)) page = i
            }
            if (page == 2) {
                val y = by - 58f * d
                val sw = (hud.width - 56f * d) / 4f
                if (hud.button("dim", "${dim}D", 16f * d, y, sw, 44f * d, ButtonStyle.SECONDARY)) { dim = if (dim >= 6) 4 else dim + 1; buildHyper() }
                if (hud.button("shape", listOf("Cube", "Cross", "Simplex")[shape], 24f * d + sw, y, sw, 44f * d, ButtonStyle.SECONDARY)) { shape = (shape + 1) % 3; buildHyper() }
                if (hud.button("plane", "Plane ${planeName()}", 32f * d + sw * 2, y, sw, 44f * d, ButtonStyle.SECONDARY)) plane = (plane + 1) % MatN.planeCount(dim)
                if (hud.button("auto", if (autoRotate) "Auto ✓" else "Auto", 40f * d + sw * 3, y, sw, 44f * d, ButtonStyle.SECONDARY)) autoRotate = !autoRotate
                hud.text("Drag to rotate in the ${planeName()} plane", hud.width / 2f, y - 26f * d, 13f, 0xAAFFFFFF.toInt(), Align.CENTER)
            }
            if (hud.iconButton("close", "✕", hud.width - hud.safeRight - 30f * d, top + 14f * d, 18f)) ctx.ui.exit(GameResult("Lab closed"))
        }
    }

    private fun planeName(): String {
        val (a, b) = MatN.plane(dim, plane % MatN.planeCount(dim))
        return MatN.axisName(a) + MatN.axisName(b)
    }

    override fun onBack(): Boolean { ctx.ui.exit(GameResult("Lab closed")); return true }

    companion object {
        const val ID = "engine-lab"
        private val ACCENT = 0xFF57C8FF.toInt()
        val descriptor = GameDescriptor(
            id = ID,
            title = "Engine Lab",
            tagline = "2D, 3D and 4D+ demos of the OEA Engine",
            description = "Live demos of the engine's rendering, ECS, particles and N-dimensional geometry. The source is a compact reference for building new games.",
            accent = ACCENT,
            glyph = "⚗",
            orientation = GameOrientation.PORTRAIT,
            dimension = GameDimension.DN,
            modes = setOf(PlayMode.SOLO),
            preferredIsland = IslandStyle.CAMERA,
            factory = { EngineLab() },
        )
    }

    @Suppress("unused") private fun unused() = cos(0f)
}
