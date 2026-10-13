package com.oea.games.chess

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader as AndroidShader
import com.oea.engine.graphics.Material
import com.oea.engine.graphics.Mesh
import com.oea.engine.graphics.MeshBuilder
import com.oea.engine.graphics.Texture
import com.oea.engine.graphics.Textures
import com.oea.engine.math.Mat4
import com.oea.engine.math.MathX
import com.oea.engine.math.Vec2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Staunton-style pieces built from turned profiles (one source of truth):
 * the same profile becomes a smooth 3D mesh (surface of revolution) and the
 * 2D icon (its silhouette), so both views always match.
 */
object ChessArt {
    /** Profile helpers: points are (radius, height) in board units (a square is 1×1). */
    private fun arc(cy: Float, rx: Float, ry: Float, fromDeg: Float, toDeg: Float, steps: Int): List<Vec2> =
        (0..steps).map { i ->
            val a = (fromDeg + (toDeg - fromDeg) * i / steps) * MathX.DEG_TO_RAD
            Vec2(maxOf(0f, cos(a) * rx), cy + sin(a) * ry)
        }

    private fun base(r: Float): List<Vec2> = listOf(
        Vec2(r, 0f), Vec2(r, 0.045f), Vec2(r - 0.03f, 0.065f), Vec2(r - 0.05f, 0.095f), Vec2(r - 0.1f, 0.115f),
    )

    val profiles: Map<Int, List<Vec2>> = mapOf(
        Piece.PAWN to base(0.31f) + listOf(Vec2(0.14f, 0.16f), Vec2(0.105f, 0.30f), Vec2(0.165f, 0.33f), Vec2(0.165f, 0.355f), Vec2(0.09f, 0.375f)) +
            arc(0.475f, 0.13f, 0.13f, -55f, 90f, 10),
        Piece.ROOK to base(0.33f) + listOf(Vec2(0.19f, 0.16f), Vec2(0.20f, 0.47f), Vec2(0.25f, 0.52f), Vec2(0.25f, 0.66f), Vec2(0.18f, 0.66f), Vec2(0.18f, 0.58f), Vec2(0f, 0.58f)),
        Piece.KNIGHT to base(0.33f) + listOf(Vec2(0.23f, 0.155f), Vec2(0.21f, 0.18f), Vec2(0f, 0.185f)),
        Piece.BISHOP to base(0.32f) + listOf(Vec2(0.15f, 0.18f), Vec2(0.10f, 0.42f), Vec2(0.17f, 0.45f), Vec2(0.17f, 0.475f), Vec2(0.11f, 0.50f)) +
            arc(0.64f, 0.14f, 0.18f, -72f, 76f, 12) + listOf(Vec2(0.018f, 0.825f)) + arc(0.865f, 0.045f, 0.045f, -60f, 90f, 6),
        Piece.QUEEN to base(0.34f) + listOf(Vec2(0.16f, 0.18f), Vec2(0.11f, 0.50f), Vec2(0.18f, 0.54f), Vec2(0.18f, 0.57f), Vec2(0.12f, 0.60f),
            Vec2(0.14f, 0.66f), Vec2(0.215f, 0.80f), Vec2(0.19f, 0.82f), Vec2(0.12f, 0.81f)) + arc(0.80f, 0.12f, 0.1f, 15f, 90f, 6) +
            arc(0.945f, 0.05f, 0.05f, -60f, 90f, 6),
        Piece.KING to base(0.35f) + listOf(Vec2(0.17f, 0.18f), Vec2(0.12f, 0.56f), Vec2(0.19f, 0.60f), Vec2(0.19f, 0.63f), Vec2(0.13f, 0.66f),
            Vec2(0.15f, 0.70f), Vec2(0.205f, 0.84f), Vec2(0.13f, 0.865f), Vec2(0.13f, 0.90f), Vec2(0f, 0.91f)),
    )

    /** Knight head silhouette (side view, nose towards +x). */
    val knightHead: List<Vec2> = listOf(
        Vec2(-0.19f, 0.17f), Vec2(-0.215f, 0.30f), Vec2(-0.20f, 0.44f), Vec2(-0.15f, 0.58f), Vec2(-0.08f, 0.69f), Vec2(-0.045f, 0.80f),
        Vec2(0.0f, 0.73f), Vec2(0.03f, 0.79f), Vec2(0.06f, 0.70f), Vec2(0.14f, 0.64f), Vec2(0.235f, 0.53f), Vec2(0.27f, 0.46f),
        Vec2(0.25f, 0.405f), Vec2(0.17f, 0.395f), Vec2(0.09f, 0.42f), Vec2(0.035f, 0.36f), Vec2(0.07f, 0.28f), Vec2(0.15f, 0.17f),
    )

    private val meshCache = HashMap<Int, Mesh>()

    /** 3D mesh for a piece type, facing +Z (towards Black for White pieces). */
    fun mesh(type: Int): Mesh = meshCache.getOrPut(type) { buildMesh(type) }

    private fun buildMesh(type: Int): Mesh {
        val mb = MeshBuilder()
        mb.lathe(profiles.getValue(type), segments = 40, capBottom = true, capTop = true, creaseDegrees = 38f)
        when (type) {
            Piece.ROOK -> for (i in 0 until 6) {
                val a = MathX.TAU * i / 6f
                mb.withTransform(Mat4().setTRS(sin(a) * 0.215f, 0.7f, cos(a) * 0.215f, yaw = a)) { box(0f, 0f, 0f, 0.1f, 0.08f, 0.07f) }
            }
            Piece.KNIGHT -> mb.withTransform(Mat4().setTRS(0f, 0f, 0f, yaw = -MathX.HALF_PI)) { extrude(knightHead, 0.2f) }
            Piece.QUEEN -> for (i in 0 until 8) {
                val a = MathX.TAU * i / 8f
                mb.sphere(sin(a) * 0.205f, 0.83f, cos(a) * 0.205f, 0.03f, 10, 6)
            }
            Piece.KING -> {
                mb.box(0f, 1.0f, 0f, 0.05f, 0.19f, 0.05f)
                mb.box(0f, 1.025f, 0f, 0.15f, 0.05f, 0.05f)
            }
        }
        return mb.build()
    }

    // ---------------------------------------------------------------- materials

    val ivory = Material(color = 0xFFF1E6D2.toInt(), specular = 0.55f, shininess = 70f, rim = 0.18f)
    val ebony = Material(color = 0xFF2B2420.toInt(), specular = 0.7f, shininess = 90f, rim = 0.28f)
    val lightSquare by lazy { Material(color = 0xFFF0D9B5.toInt(), texture = Textures.wood(256, 0xFFF3DDB8.toInt(), 0xFFD9B98B.toInt(), 5), specular = 0.25f, shininess = 40f) }
    val darkSquare by lazy { Material(color = 0xFFB58863.toInt(), texture = Textures.wood(256, 0xFFB98D63.toInt(), 0xFF8A5D3B.toInt(), 9), specular = 0.25f, shininess = 40f) }
    val frame by lazy { Material(color = 0xFF5B3A24.toInt(), texture = Textures.wood(256, 0xFF6E4A30.toInt(), 0xFF3E2716.toInt(), 13), specular = 0.35f, shininess = 50f) }
    val table by lazy { Material(color = 0xFF2A2F38.toInt(), texture = Textures.noise(256, 24f, 4, 0xFF262B33.toInt(), 0xFF323844.toInt()), specular = 0.05f, receiveShadows = true) }

    /** Board tiles merged into two meshes (light and dark), plus the frame. */
    val lightTiles: Mesh by lazy { tiles(light = true) }
    val darkTiles: Mesh by lazy { tiles(light = false) }
    val frameMesh: Mesh by lazy {
        val mb = MeshBuilder()
        val outer = 4.55f; val inner = 4.0f; val y0 = -0.16f; val y1 = 0.015f
        mb.boxMinMax(-outer, y0, -outer, outer, y1, -inner, uvScale = 0.5f)
        mb.boxMinMax(-outer, y0, inner, outer, y1, outer, uvScale = 0.5f)
        mb.boxMinMax(-outer, y0, -inner, -inner, y1, inner, uvScale = 0.5f)
        mb.boxMinMax(inner, y0, -inner, outer, y1, inner, uvScale = 0.5f)
        mb.boxMinMax(-inner, y0, -inner, inner, -0.075f, inner)
        mb.build()
    }

    private fun tiles(light: Boolean): Mesh {
        val mb = MeshBuilder()
        for (rank in 0 until 8) for (file in 0 until 8) {
            if (((rank + file) % 2 == 1) != light) continue
            val x = 3.5f - file; val z = rank - 3.5f
            mb.box(x, -0.04f, z, 1f, 0.08f, 1f)
        }
        return mb.build()
    }

    // ---------------------------------------------------------------- 2D icons

    private const val CELL = 160

    /** Atlas: columns = piece type 1..6, rows = white, black. */
    val icons: Texture by lazy {
        Texture.canvas(1024, 512, mipmaps = true) { c ->
            for (color in 0..1) for (type in 1..6) drawIcon(c, (type - 1) * CELL + 32f, color * CELL + 32f, CELL.toFloat(), type, color)
        }
    }

    /** UV rectangle of [piece] in [icons]: u0, v0, u1, v1. */
    fun iconUv(piece: Int, out: FloatArray) {
        val type = Piece.type(piece); val color = Piece.color(piece)
        out[0] = ((type - 1) * CELL + 32f) / 1024f
        out[1] = (color * CELL + 32f) / 512f
        out[2] = ((type - 1) * CELL + 32f + CELL) / 1024f
        out[3] = (color * CELL + 32f + CELL) / 512f
    }

    private fun drawIcon(c: Canvas, x: Float, y: Float, size: Float, type: Int, color: Int) {
        val scale = size / 1.18f
        val cx = x + size / 2f
        val baseY = y + size - size * 0.06f
        val path = Path()
        fun px(r: Float) = r * scale
        val prof = profiles.getValue(type)
        // Right side bottom→top, then left side top→bottom.
        path.moveTo(cx - px(prof[0].x), baseY)
        for (p in prof) path.lineTo(cx + px(p.x), baseY - px(p.y))
        for (p in prof.asReversed()) path.lineTo(cx - px(p.x), baseY - px(p.y))
        path.close()
        when (type) {
            Piece.KNIGHT -> {
                val head = Path()
                knightHead.forEachIndexed { i, p ->
                    val hx = cx - px(p.x) * 1.05f; val hy = baseY - px(p.y)
                    if (i == 0) head.moveTo(hx, hy) else head.lineTo(hx, hy)
                }
                head.close()
                path.op(head, Path.Op.UNION)
            }
            Piece.ROOK -> for (i in -1..1) {
                val r = Path(); r.addRect(cx + px(i * 0.16f - 0.05f), baseY - px(0.75f), cx + px(i * 0.16f + 0.05f), baseY - px(0.64f), Path.Direction.CW)
                path.op(r, Path.Op.UNION)
            }
            Piece.KING -> {
                val k = Path()
                k.addRect(cx - px(0.028f), baseY - px(1.1f), cx + px(0.028f), baseY - px(0.9f), Path.Direction.CW)
                k.addRect(cx - px(0.08f), baseY - px(1.05f), cx + px(0.08f), baseY - px(0.995f), Path.Direction.CW)
                path.op(k, Path.Op.UNION)
            }
            Piece.QUEEN -> for (i in -2..2) {
                val q = Path(); q.addCircle(cx + px(i * 0.09f), baseY - px(0.84f - kotlin.math.abs(i) * 0.012f), px(0.033f), Path.Direction.CW)
                path.op(q, Path.Op.UNION)
            }
        }
        val white = color == Piece.WHITE
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, y, 0f, y + size, if (white) intArrayOf(0xFFFFFFFF.toInt(), 0xFFE3D3B6.toInt()) else intArrayOf(0xFF4A4038.toInt(), 0xFF141110.toInt()),
                null, AndroidShader.TileMode.CLAMP)
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = size * 0.035f
            strokeJoin = Paint.Join.ROUND
            this.color = if (white) 0xFF3A2C20.toInt() else 0xFFD8CCB8.toInt()
        }
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0x33000000 }
        c.save(); c.translate(size * 0.02f, size * 0.03f); c.drawPath(path, shadow); c.restore()
        c.drawPath(path, fill)
        c.drawPath(path, stroke)
    }
}
