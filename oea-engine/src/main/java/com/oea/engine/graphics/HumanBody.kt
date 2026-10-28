package com.oea.engine.graphics

import com.oea.engine.math.Colors
import com.oea.engine.math.MathX
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Builds the skinned human character — the whole body from one
 * [CharSpec] — and bakes it into a handful of [SkeletalMesh] parts. This is
 * what turns OEA's character system into a real humanoid: height,
 * proportions (shoulders, hips, chest, limbs), face proportions (eyes, nose,
 * jaw), hair style, and the outfit layers are all read from the same
 * CharSpec the studio sliders drive, then turned into vertices weighted to
 * [Skeleton] bones so the joints actually bend.
 *
 * Each material group gets its own mesh so draw calls stay cheap:
 * [body] skin (torso under the shirt, head, hands, bare limbs), [shirt],
 * [pants], [hair], [shoes], [outer] (jacket/hoodie/coat shell), [headwear]
 * (helmet/cap/beanie/hijab), [vest] (armor), [pack] (backpack). Geometry is
 * generated on construction — thousands of vertices, done once per
 * character, at a cost the studio's live sliders can afford.
 */
class HumanBody(spec: CharSpec?, feminine: Boolean, hairStyle: Int) {
    val body: SkeletalMesh
    /** Eyes on their own skinned mesh (HEAD bone): the draw squashes it in Y to blink. */
    val face: SkeletalMesh
    /** Brows on their own mesh so tension can flatten them (furrow) independently of the blink. */
    val brows: SkeletalMesh
    val shirt: SkeletalMesh
    val pants: SkeletalMesh
    val hair: SkeletalMesh
    val shoes: SkeletalMesh
    val outer: SkeletalMesh
    val headwear: SkeletalMesh
    val vest: SkeletalMesh
    val pack: SkeletalMesh

    private companion object {
        // Bone ordinals (Skeleton.Bone).
        const val PELVIS = 0; const val SPINE = 1; const val CHEST = 2; const val NECK = 3; const val HEAD = 4
        const val SHOUL_L = 5; const val ARM_L = 6; const val FORE_L = 7; const val HAND_L = 8
        const val SHOUL_R = 9; const val ARM_R = 10; const val FORE_R = 11; const val HAND_R = 12
        const val THIGH_L = 13; const val SHIN_L = 14; const val FOOT_L = 15
        const val THIGH_R = 16; const val SHIN_R = 17; const val FOOT_R = 18

        const val WHITE = Colors.WHITE
        const val EYE = 0xFF1E1610.toInt()
        const val MOUTH = 0xFF8A4A3A.toInt()
        const val SCAR = 0xFF5A2A20.toInt()
    }

    init {
        val form = spec?.form()
        val fem = feminine || (form?.feminine == true)

        // ---- body proportions from CharForm sliders
        val shoulderK = (form?.shoulderScale ?: 1f) * (if (fem) 0.90f else 1f)
        val hipK = (form?.hipScale ?: 1f) * (if (fem) 1.10f else 1f)
        val chestK = (form?.chestDepth ?: 1f) * (if (fem) 0.88f else 1f)
        val waistK = form?.waistScale ?: 1f
        val limbT = (form?.limbScale ?: 1f)
        val faceW = form?.faceWidth ?: 1f
        val faceL = form?.faceLength ?: 1f
        val jawW = form?.jawWidth ?: 1f
        val jawD = form?.jawDepth ?: 1f
        val chinL = form?.chinLength ?: 1f
        val eyeS = form?.eyeSize ?: 1f
        val eyeSp = form?.eyeSpacing ?: 1f
        val eyeD = form?.eyeDepth ?: 1f
        val noseW = form?.noseWidth ?: 1f
        val mouthW = form?.mouthWidth ?: 1f
        val browR = form?.browRidge ?: 1f
        val earS = form?.earSize ?: 1f

        // ---- clothing parts from the catalog (ids + fit/coverage hints)
        val topPart = CharParts.TOPS.firstOrNull { it.id == (form?.top ?: "tee") } ?: CharParts.TOPS[0]
        val bottomPart = CharParts.BOTTOMS.firstOrNull { it.id == (form?.bottom ?: "cargo") } ?: CharParts.BOTTOMS[0]
        val bottomCov = bottomPart.coverage
        val outerId = form?.outer ?: "none"
        val outerPart = CharParts.OUTERWEAR.firstOrNull { it.id == outerId }
        val headwearId = form?.headwear ?: "none"

        val hairStyle = if (spec != null) (form?.hairStyle ?: hairStyle).coerceIn(0, 5) else hairStyle.coerceIn(0, 5)
        val hairColor = form?.hairColor ?: Colors.DK_BROWN
        val skinColor = form?.skinColor ?: Colors.DK_BROWN
        val eyeColor = form?.eyeColor ?: EYE

        body = buildBody(
            faceW, faceL, jawW, jawD, chinL, noseW, mouthW, browR, earS,
            limbT, skinColor, topPart.coverage, bottomCov, form,
        )
        face = buildFace(eyeS, eyeSp, eyeColor, faceW, faceL)
        brows = buildBrows(eyeSp, browR)
        shirt = buildShirt(topPart, chestK, shoulderK, waistK, hipK)
        pants = buildPants(bottomPart, hipK, limbT)
        hair = buildHair(hairStyle, hairColor, faceW, faceL, browR, eyeSp, jawW, (form?.facialHair ?: 0).toFloat())
        shoes = buildShoes(limbT)
        outer = buildOuter(outerPart, chestK, shoulderK, waistK, hipK)
        headwear = buildHeadwear(headwearId)
        vest = buildVest()
        pack = buildPack()
    }

    // ------------------------------------------------------------------
    // Geometry primitives
    // ------------------------------------------------------------------

    private class Ring(val y: Float, val rx: Float, val rz: Float, val weights: List<Pair<Int, Float>>)

    /** A vertical column of elliptical rings (torso, neck, skirt). */
    private fun stack(g: SkeletalMesh.Builder, segs: Int, rings: List<Ring>, color: Int) {
        val base = g.vertexCount
        for (ring in rings) {
            for (k in 0..segs) {
                val a = MathX.TAU * k / segs
                val ca = cos(a); val sa = sin(a)
                g.addVertex(ring.rx * ca, ring.y, ring.rz * sa, ca, 0f, sa, k / segs.toFloat(), 0f, color, ring.weights)
            }
        }
        for (j in 0 until rings.size - 1) {
            for (k in 0 until segs) {
                val a = base + j * (segs + 1) + k
                g.tri(a, a + segs + 1, a + segs + 2)
                g.tri(a, a + segs + 2, a + 1)
            }
        }
    }

    /** A tapered limb between two bone origins (limbs, tail, nose). */
    private fun tube(
        g: SkeletalMesh.Builder,
        ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float,
        rA: Float, rB: Float, segs: Int, rings: Int,
        boneA: Int, boneB: Int, color: Int,
        depthA: Float = 1f, depthB: Float = 1f,
        /** Joint bulge: a radius bump near the boneB end — reads as muscle/
         * clothing deformation when the joint bends, invisible when straight. */
        bulge: Float = 0f,
    ) {
        val dx = bx - ax; val dy = by - ay; val dz = bz - az
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-5f) return
        val ux = dx / len; val uy = dy / len; val uz = dz / len
        var rx = uz; var ry = 0f; var rz = -ux
        val rl = sqrt(rx * rx + rz * rz)
        if (rl < 1e-4f) { rx = 1f; rz = 0f } else { rx /= rl; rz /= rl }
        val fx = uy * rz - uz * ry; val fy = uz * rx - ux * rz; val fz = ux * ry - uy * rx
        val base = g.vertexCount
        for (j in 0..rings) {
            val t = j / rings.toFloat()
            val px = ax + dx * t; val py = ay + dy * t; val pz = az + dz * t
            // Joint bulge: a smooth bump near the boneB end (the joint), fading
            // at the very tip where the next limb takes over.
            val b = 1f + bulge * MathX.smoothstep(0.55f, 0.92f, t) * (1f - MathX.smoothstep(0.92f, 1f, t))
            val r = (rA + (rB - rA) * t) * b
            val d = depthA + (depthB - depthA) * t
            val wb = MathX.smoothstep(0.72f, 1f, t)
            for (k in 0..segs) {
                val a = MathX.TAU * k / segs
                val ca = cos(a); val sa = sin(a)
                val ox = rx * r * ca + fx * r * d * sa
                val oy = ry * r * ca + fy * r * d * sa
                val oz = rz * r * ca + fz * r * d * sa
                val nl = sqrt(ox * ox + oy * oy + oz * oz).let { if (it < 1e-6f) 1f else it }
                g.addVertex(px + ox, py + oy, pz + oz, ox / nl, oy / nl, oz / nl, k / segs.toFloat(), t, color,
                    listOf(boneA to 1f - wb, boneB to wb))
            }
        }
        for (j in 0 until rings) {
            for (k in 0 until segs) {
                val a = base + j * (segs + 1) + k
                g.tri(a, a + 1, a + segs + 2)
                g.tri(a, a + segs + 2, a + segs + 1)
            }
        }
    }

    /** An ellipsoid clipped to theta/phi ranges (spheres, caps, hoods). */
    private fun partialSphere(
        g: SkeletalMesh.Builder,
        cx: Float, cy: Float, cz: Float, rx: Float, ry: Float, rz: Float,
        segs: Int, rings: Int, bone: Int, color: Int,
        phiMax: Float = MathX.PI_F, theta0: Float = 0f, theta1: Float = MathX.TAU,
    ) {
        val base = g.vertexCount
        for (j in 0..rings) {
            val t = j / rings.toFloat()
            val phi = phiMax * t
            val sp = sin(phi); val cp = cos(phi)
            val rj = sp * rx
            val sy = cp * ry
            for (k in 0..segs) {
                val theta = theta0 + (theta1 - theta0) * k / segs
                val st = sin(theta); val ct = cos(theta)
                val ox = rj * ct
                val oy = sy
                val oz = rj * st
                val nl = sqrt(ox * ox + oy * oy + oz * oz).let { if (it < 1e-6f) 1f else it }
                g.addVertex(cx + ox, cy + oy, cz + oz, ox / nl, oy / nl, oz / nl, k / segs.toFloat(), t, color, listOf(bone to 1f))
            }
        }
        for (j in 0 until rings) {
            for (k in 0 until segs) {
                val a = base + j * (segs + 1) + k
                g.tri(a, a + 1, a + segs + 2)
                g.tri(a, a + segs + 2, a + segs + 1)
            }
        }
    }

    private fun torus(g: SkeletalMesh.Builder, cx: Float, cy: Float, cz: Float, r: Float, tubeR: Float, segs: Int, segs2: Int, bone: Int, color: Int) {
        val base = g.vertexCount
        for (j in 0..segs2) {
            val phi = MathX.PI_F * j / segs2
            val cp = cos(phi); val sp = sin(phi)
            val rr = r + tubeR * cp
            for (k in 0..segs) {
                val theta = MathX.TAU * k / segs
                val ct = cos(theta); val st = sin(theta)
                val ox = rr * ct
                val oy = tubeR * sp
                val oz = rr * st
                g.addVertex(cx + ox, cy + oy, cz + oz, ct * cp, sp, st * cp, k / segs.toFloat(), j / segs2.toFloat(), color, listOf(bone to 1f))
            }
        }
        for (j in 0 until segs2) {
            for (k in 0 until segs) {
                val a = base + j * (segs + 1) + k
                g.tri(a, a + segs + 1, a + segs + 2)
                g.tri(a, a + segs + 2, a + 1)
            }
        }
    }

    private fun box(g: SkeletalMesh.Builder, cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float, weights: List<Pair<Int, Float>>, color: Int) {
        val hx = sx * 0.5f; val hy = sy * 0.5f; val hz = sz * 0.5f
        val x0 = cx - hx; val x1 = cx + hx
        val y0 = cy - hy; val y1 = cy + hy
        val z0 = cz - hz; val z1 = cz + hz
        // corner bit0 = x1, bit1 = y1, bit2 = z1
        val px = Array(8) { idx -> if (idx and 1 != 0) x1 else x0 }
        val py = Array(8) { idx -> if (idx and 2 != 0) y1 else y0 }
        val pz = Array(8) { idx -> if (idx and 4 != 0) z1 else z0 }
        val ux = Array(8) { idx -> (px[idx] - cx) / max(sx, 1e-5f) + 0.5f }
        val uy = Array(8) { idx -> (py[idx] - cy) / max(sy, 1e-5f) + 0.5f }
        val uz = Array(8) { idx -> (pz[idx] - cz) / max(sz, 1e-5f) + 0.5f }
        // face = (normal, 4 corners CCW from outside, u-axis, v-axis)
        data class Face(val nx: Float, val ny: Float, val nz: Float, val c: IntArray, val ua: Int, val va: Int)
        val faces = listOf(
            Face(0f, 0f, -1f, intArrayOf(0, 2, 3, 1), 0, 1),
            Face(0f, 0f, 1f, intArrayOf(4, 5, 7, 6), 0, 1),
            Face(-1f, 0f, 0f, intArrayOf(0, 4, 6, 2), 2, 1),
            Face(1f, 0f, 0f, intArrayOf(1, 3, 7, 5), 2, 1),
            Face(0f, -1f, 0f, intArrayOf(0, 1, 5, 4), 0, 2),
            Face(0f, 1f, 0f, intArrayOf(2, 6, 7, 3), 0, 2),
        )
        for (face in faces) {
            val v = IntArray(4)
            for (i in 0 until 4) {
                val idx = face.c[i]
                val uu = if (face.ua == 0) ux[idx] else if (face.ua == 1) uy[idx] else uz[idx]
                val vv = if (face.va == 0) ux[idx] else if (face.va == 1) uy[idx] else uz[idx]
                v[i] = g.addVertex(px[idx], py[idx], pz[idx], face.nx, face.ny, face.nz, uu, vv, color, weights)
            }
            g.tri(v[0], v[1], v[2])
            g.tri(v[0], v[2], v[3])
        }
    }

    private fun buildBody(
        faceW: Float, faceL: Float, jawW: Float, jawD: Float, chinL: Float,
        noseW: Float, mouthW: Float, browR: Float, earS: Float,
        limbT: Float, skin: Int, topCov: Float, bottomCov: Float, form: CharForm?,
    ): SkeletalMesh {
        val g = SkeletalMesh.Builder()
        // pelvis block (under the shirt)
        stack(g, 14, listOf(
            Ring(0.64f, 0.145f, 0.115f, listOf(PELVIS to 1f)),
            Ring(0.70f, 0.165f, 0.13f, listOf(PELVIS to 1f)),
            Ring(0.78f, 0.162f, 0.125f, listOf(PELVIS to 1f)),
            Ring(0.88f, 0.156f, 0.118f, listOf(PELVIS to 1f)),
        ), skin)
        // neck
        stack(g, 14, listOf(
            Ring(1.42f, 0.155f, 0.115f, listOf(CHEST to 0.5f, NECK to 0.5f)),
            Ring(1.46f, 0.11f, 0.11f, listOf(NECK to 0.6f, HEAD to 0.4f)),
            Ring(1.50f, 0.09f, 0.09f, listOf(NECK to 0.4f, HEAD to 0.6f)),
        ), skin)
        // head
        partialSphere(g, 0f, 1.62f, 0f, 0.108f * faceW, 0.108f, 0.108f * faceL, 14, 10, HEAD, skin)
        // jaw
        partialSphere(g, 0f, 1.505f + (chinL - 1f) * 0.05f, (chinL - 1f) * 0.03f,
            0.085f * jawW, 0.055f * jawD, 0.085f * jawW, 12, 8, HEAD, skin)
        // nose
        tube(g, 0f, 1.555f, 0.086f, 0f, 1.535f + (chinL - 1f) * 0.03f, 0.138f,
            0.021f * noseW, 0.014f * noseW, 8, 3, HEAD, HEAD, skin, depthA = 0.9f, depthB = 0.7f)
        // ears
        partialSphere(g, 0.103f * faceW, 1.595f, -0.005f, 0.028f * earS, 0.042f * earS, 0.02f * earS, 8, 6, HEAD, skin)
        partialSphere(g, -0.103f * faceW, 1.595f, -0.005f, 0.028f * earS, 0.042f * earS, 0.02f * earS, 8, 6, HEAD, skin)
        // mouth on the jaw
        partialSphere(g, 0f, 1.528f + (chinL - 1f) * 0.02f, 0.098f,
            0.026f * mouthW, 0.008f, 0.009f * mouthW + 0.006f, 10, 3, HEAD, MOUTH)
        // freckles
        if ((form?.freckles ?: 0f) > 0.05f) {
            val fs = Colors.scale(skin, 0.7f)
            val spots = listOf(
                floatArrayOf(0.052f, 1.585f, 0.090f), floatArrayOf(0.068f, 1.582f, 0.093f),
                floatArrayOf(0.046f, 1.578f, 0.095f), floatArrayOf(-0.052f, 1.585f, 0.090f),
                floatArrayOf(-0.068f, 1.582f, 0.093f), floatArrayOf(-0.046f, 1.578f, 0.095f),
            )
            for (sp in spots) {
                partialSphere(g, sp[0], sp[1], sp[2], 0.008f, 0.007f, 0.005f, 6, 4, HEAD, fs)
            }
        }
        // scar
        if ((form?.scar ?: 0f) > 0.05f) {
            partialSphere(g, -0.075f, 1.59f, 0.092f, 0.008f, 0.03f * (form?.scar ?: 0f) + 0.015f, 0.006f, 6, 4, HEAD, SCAR)
        }
        // warpaint streaks
        if ((form?.warpaint ?: 0f) > 0.05f) {
            val wc = form?.paintColor ?: 0xFFB04030.toInt()
            partialSphere(g, 0.088f, 1.585f, 0.092f, 0.013f, 0.05f, 0.008f, 6, 4, HEAD, wc)
            partialSphere(g, -0.088f, 1.585f, 0.092f, 0.013f, 0.05f, 0.008f, 6, 4, HEAD, wc)
        }
        // forearms + hands
        for (side in intArrayOf(1, -1)) {
            val arm = if (side < 0) ARM_R else ARM_L
            val fore = if (side < 0) FORE_R else FORE_L
            val hand = if (side < 0) HAND_R else HAND_L
            val sx = side * 0.18f
            tube(g, sx, 1.18f, 0f, sx, 0.92f, 0f, 0.058f * limbT, 0.046f * limbT, 10, 4, arm, fore, skin, bulge = 0.10f)
            partialSphere(g, sx, 0.87f, 0.01f, 0.052f * limbT, 0.052f * limbT, 0.03f * limbT, 10, 6, hand, skin)
        }
        // bare upper arms under tank tops
        if (topCov < 0.25f) {
            for (side in intArrayOf(1, -1)) {
                val sho = if (side < 0) SHOUL_R else SHOUL_L
                val arm = if (side < 0) ARM_R else ARM_L
                tube(g, side * 0.18f, 1.44f, 0f, side * 0.18f, 1.18f, 0f, 0.082f * limbT, 0.066f * limbT, 10, 4, sho, arm, skin, bulge = 0.11f)
            }
        }
        // bare shins under shorts/skirts
        if (bottomCov < 1f) {
            for (side in intArrayOf(1, -1)) {
                val shin = if (side < 0) SHIN_R else SHIN_L
                val foot = if (side < 0) FOOT_R else FOOT_L
                tube(g, side * 0.10f, 0.44f, 0f, side * 0.10f, 0.02f, 0f, 0.09f * limbT, 0.06f * limbT, 10, 4, shin, foot, skin, bulge = 0.10f)
            }
        }
        return g.build()
    }

    private fun buildShirt(topPart: CharParts.Part, chestK: Float, shoulderK: Float, waistK: Float, hipK: Float): SkeletalMesh {
        val g = SkeletalMesh.Builder()
        val fit = 1f + topPart.fit * 0.18f
        stack(g, 14, torsoProfile(fit, chestK, shoulderK, waistK, hipK), WHITE)
        if (topPart.coverage > 0.25f) {
            for (side in intArrayOf(1, -1)) {
                val sho = if (side < 0) SHOUL_R else SHOUL_L
                val arm = if (side < 0) ARM_R else ARM_L
                tube(g, side * 0.18f, 1.44f, 0f, side * 0.18f, 1.18f, 0f, 0.085f, 0.068f, 10, 4, sho, arm, WHITE, bulge = 0.09f)
            }
        }
        if (topPart.collar) {
            torus(g, 0f, 1.44f, 0f, 0.105f, 0.018f, 14, 6, NECK, WHITE)
        }
        return g.build()
    }

    private fun torsoProfile(fit: Float, chestK: Float, shoulderK: Float, waistK: Float, hipK: Float): List<Ring> {
        return listOf(
            Ring(0.70f, 0.165f * hipK * fit, 0.13f * fit, listOf(PELVIS to 1f)),
            Ring(0.76f, 0.162f * hipK * fit, 0.125f * fit, listOf(PELVIS to 0.5f, SPINE to 0.5f)),
            Ring(0.84f, 0.156f * fit, 0.12f * fit, listOf(SPINE to 1f)),
            Ring(0.92f, 0.148f * waistK * fit, 0.115f * fit, listOf(SPINE to 1f)),
            Ring(0.98f, 0.162f * fit, 0.124f * fit, listOf(SPINE to 0.5f, CHEST to 0.5f)),
            Ring(1.06f, 0.188f * chestK * fit, 0.145f * chestK * fit, listOf(CHEST to 1f)),
            Ring(1.16f, 0.205f * chestK * fit, 0.152f * chestK * fit, listOf(CHEST to 1f)),
            Ring(1.24f, 0.215f * shoulderK * fit, 0.152f * fit, listOf(CHEST to 1f)),
            Ring(1.30f, 0.21f * shoulderK * fit, 0.148f * fit, listOf(CHEST to 0.7f, SHOUL_L to 0.15f, SHOUL_R to 0.15f)),
            Ring(1.36f, 0.19f * fit, 0.135f * fit, listOf(CHEST to 1f)),
            Ring(1.42f, 0.155f * fit, 0.115f * fit, listOf(CHEST to 0.5f, NECK to 0.5f)),
        )
    }

    private fun buildPants(bottomPart: CharParts.Part, hipK: Float, limbT: Float): SkeletalMesh {
        val g = SkeletalMesh.Builder()
        val fit = 1f + bottomPart.fit * 0.16f
        stack(g, 14, listOf(
            Ring(0.64f, 0.150f * fit, 0.118f * fit, listOf(PELVIS to 1f)),
            Ring(0.70f, 0.172f * hipK * fit, 0.133f * fit, listOf(PELVIS to 1f)),
            Ring(0.78f, 0.166f * hipK * fit, 0.126f * fit, listOf(PELVIS to 1f)),
            Ring(0.88f, 0.160f * hipK * fit, 0.119f * fit, listOf(PELVIS to 1f)),
        ), WHITE)
        if (bottomPart.id == "skirt") {
            stack(g, 14, listOf(
                Ring(0.68f, 0.19f * hipK * fit, 0.145f * fit, listOf(PELVIS to 1f)),
                Ring(0.60f, 0.245f * hipK * fit, 0.19f * fit, listOf(PELVIS to 1f)),
            ), WHITE)
            return g.build()
        }
        for (side in intArrayOf(1, -1)) {
            val thigh = if (side < 0) THIGH_R else THIGH_L
            val shin = if (side < 0) SHIN_R else SHIN_L
            val foot = if (side < 0) FOOT_R else FOOT_L
            val sx = side * 0.10f
            val legEnd = if (bottomPart.coverage < 0.75f) 0.55f else 0.02f
            tube(g, sx, 0.88f, 0f, sx, legEnd, 0f, 0.124f * limbT, 0.100f * limbT, 10, 5, thigh, shin, WHITE, bulge = 0.14f)
            if (legEnd <= 0.02f) {
                tube(g, sx, 0.44f, 0f, sx, 0.02f, 0f, 0.092f * limbT, 0.062f * limbT, 10, 4, shin, foot, WHITE, bulge = 0.10f)
            }
        }
        return g.build()
    }

    /**
     * The eyes as their own skinned mesh, bound to the HEAD bone. Geometry is
     * centered on the eye line (y = 0) so the draw can squash the mesh in Y to
     * blink — the rest of the face stays put. Brows stay in the hair cap.
     */
    private fun buildFace(eyeS: Float, eyeSp: Float, eyeColor: Int, faceW: Float, faceL: Float): SkeletalMesh {
        val g = SkeletalMesh.Builder()
        val eyeZ = 0.102f * faceL
        partialSphere(g, 0.045f * eyeSp, 0f, eyeZ, 0.021f * eyeS, 0.021f * eyeS, 0.014f * eyeS, 8, 6, HEAD, eyeColor)
        partialSphere(g, -0.045f * eyeSp, 0f, eyeZ, 0.021f * eyeS, 0.021f * eyeS, 0.014f * eyeS, 8, 6, HEAD, eyeColor)
        return g.build()
    }

    /**
     * The brows as their own skinned mesh (HEAD bone), centered on the eye
     * line like the eyes. The draw flattens them in Y to furrow.
     */
    private fun buildBrows(eyeSp: Float, browR: Float): SkeletalMesh {
        val g = SkeletalMesh.Builder()
        partialSphere(g, 0.045f * eyeSp, 0.03f, 0.096f, 0.022f * browR, 0.009f, 0.012f, 8, 3, HEAD, WHITE)
        partialSphere(g, -0.045f * eyeSp, 0.03f, 0.096f, 0.022f * browR, 0.009f, 0.012f, 8, 3, HEAD, WHITE)
        return g.build()
    }

    private fun buildHair(style: Int, hairColor: Int, faceW: Float, faceL: Float, browR: Float, eyeSp: Float, jawW: Float, facial: Float): SkeletalMesh {
        val g = SkeletalMesh.Builder()
        val cx = 0f; val cy = 1.625f; val cz = -0.005f
        val rx = 0.12f * faceW; val ry = 0.12f; val rz = 0.12f * faceL
        val theta0 = 0.45f
        when (style) {
            1 -> { // ponytail: cap + tail
                partialSphere(g, cx, cy, cz, rx, ry, rz, 16, 12, HEAD, WHITE, phiMax = MathX.PI_F * 0.68f, theta0, MathX.TAU - theta0)
                tube(g, 0f, 1.58f, -0.10f, 0f, 1.30f, -0.21f, 0.055f, 0.022f, 10, 5, HEAD, NECK, WHITE)
            }
            2 -> { // bun
                partialSphere(g, cx, cy, cz, rx, ry, rz, 16, 12, HEAD, WHITE, phiMax = MathX.PI_F * 0.68f, theta0, MathX.TAU - theta0)
                partialSphere(g, 0f, 1.755f, -0.045f, 0.075f, 0.07f, 0.075f, 12, 8, HEAD, WHITE)
            }
            3 -> { // long: curtains + back fall
                partialSphere(g, cx, cy, cz, rx, ry, rz, 16, 12, HEAD, WHITE, phiMax = MathX.PI_F * 0.72f, theta0, MathX.TAU - theta0)
                for (side in intArrayOf(1, -1)) {
                    tube(g, side * 0.105f, 1.60f, 0.02f, side * 0.125f, 1.32f, 0.005f, 0.034f, 0.028f, 8, 5, HEAD, NECK, WHITE)
                }
                tube(g, 0f, 1.58f, -0.10f, 0f, 1.22f, -0.17f, 0.072f, 0.09f, 12, 6, HEAD, NECK, WHITE)
            }
            4 -> { // buzz: tight cap
                partialSphere(g, cx, cy, cz, 0.114f * faceW, 0.114f, 0.114f * faceL, 16, 10, HEAD, WHITE,
                    phiMax = MathX.PI_F * 0.52f, theta0 = 0.4f, theta1 = MathX.TAU - 0.4f)
            }
            5 -> { // mohawk ridge
                partialSphere(g, cx, cy, cz, rx, ry, rz, 16, 12, HEAD, WHITE, phiMax = MathX.PI_F * 0.68f, theta0, MathX.TAU - theta0)
                for (k in 0..4) {
                    partialSphere(g, 0f, 1.70f + k * 0.05f, -0.05f + k * 0.035f, 0.05f - k * 0.008f, 0.048f - k * 0.007f, 0.05f - k * 0.008f, 8, 6, HEAD, WHITE)
                }
            }
            else -> { // short
                partialSphere(g, cx, cy, cz, rx, ry, rz, 16, 12, HEAD, WHITE, phiMax = MathX.PI_F * 0.68f, theta0, MathX.TAU - theta0)
            }
        }
        // facial hair
        if (facial > 0f) {
            val beardK = if (facial > 0.5f) 1f else 0.55f
            partialSphere(g, 0f, 1.50f, 0.02f, 0.085f * jawW, 0.065f * beardK, 0.075f * jawW, 10, 6, HEAD, Colors.scale(hairColor, 0.82f))
            partialSphere(g, 0f, 1.565f, 0.07f, 0.018f * jawW, 0.022f * beardK, 0.012f, 8, 4, HEAD, Colors.scale(hairColor, 0.82f))
        }
        return g.build()
    }

    private fun buildShoes(limbT: Float): SkeletalMesh {
        val g = SkeletalMesh.Builder()
        for (side in intArrayOf(1, -1)) {
            val foot = if (side < 0) FOOT_R else FOOT_L
            val shin = if (side < 0) SHIN_R else SHIN_L
            box(g, side * 0.10f, 0.035f, 0.045f, 0.105f * limbT, 0.07f * limbT, 0.22f * limbT,
                listOf(foot to 0.85f, shin to 0.15f), WHITE)
            partialSphere(g, side * 0.10f, 0.06f, 0.005f, 0.052f * limbT, 0.035f * limbT, 0.05f * limbT, 8, 6, foot, WHITE)
        }
        return g.build()
    }

    private fun buildOuter(outer: CharParts.Part?, chestK: Float, shoulderK: Float, waistK: Float, hipK: Float): SkeletalMesh {
        val g = SkeletalMesh.Builder()
        if (outer == null) return g.build()
        val fit = 1f + outer.fit * 0.22f
        stack(g, 14, torsoProfile(fit, chestK, shoulderK, waistK, hipK), WHITE)
        if (outer.id == "coat") {
            stack(g, 14, listOf(
                Ring(0.66f, 0.18f * hipK * fit, 0.138f * fit, listOf(PELVIS to 1f)),
                Ring(0.55f, 0.21f * hipK * fit, 0.155f * fit, listOf(PELVIS to 1f)),
            ), WHITE)
        }
        if (outer.id == "hoodie") {
            partialSphere(g, 0f, 1.56f, -0.05f, 0.115f, 0.10f, 0.11f, 12, 8, NECK, WHITE,
                phiMax = MathX.PI_F * 0.55f, theta0 = 0.7f, theta1 = MathX.TAU - 0.7f)
        }
        return g.build()
    }

    private fun buildHeadwear(partId: String): SkeletalMesh {
        val g = SkeletalMesh.Builder()
        val cy = 1.655f
        when (partId) {
            "helmet" -> {
                partialSphere(g, 0f, cy, -0.005f, 0.138f, 0.138f, 0.138f, 16, 10, HEAD, WHITE)
                box(g, 0f, 1.645f, 0.112f, 0.165f, 0.04f, 0.09f, listOf(HEAD to 1f), WHITE)
            }
            "cap" -> {
                partialSphere(g, 0f, cy - 0.005f, -0.003f, 0.128f, 0.128f, 0.128f, 16, 10, HEAD, WHITE,
                    phiMax = MathX.PI_F * 0.56f)
                box(g, 0f, 1.63f, 0.125f, 0.185f, 0.03f, 0.11f, listOf(HEAD to 1f), WHITE)
            }
            "beanie" -> {
                partialSphere(g, 0f, cy, -0.005f, 0.131f, 0.131f, 0.131f, 16, 10, HEAD, WHITE,
                    phiMax = MathX.PI_F * 0.6f)
            }
            "hijab" -> {
                partialSphere(g, 0f, cy - 0.01f, -0.005f, 0.138f, 0.138f, 0.138f, 16, 10, HEAD, WHITE,
                    phiMax = MathX.PI_F * 0.95f, theta0 = 0.3f, theta1 = MathX.TAU - 0.3f)
                tube(g, 0f, 1.52f, -0.05f, 0f, 1.30f, -0.11f, 0.105f, 0.125f, 12, 5, HEAD, NECK, WHITE)
            }
        }
        return g.build()
    }

    private fun buildVest(): SkeletalMesh {
        val g = SkeletalMesh.Builder()
        box(g, 0f, 1.30f, 0.01f, 0.58f, 0.52f, 0.38f, listOf(CHEST to 0.75f, SPINE to 0.25f), WHITE)
        box(g, -0.17f, 1.34f, -0.04f, 0.05f, 0.05f, 0.30f, listOf(CHEST to 0.8f, SHOUL_L to 0.2f), WHITE)
        box(g, 0.17f, 1.34f, -0.04f, 0.05f, 0.05f, 0.30f, listOf(CHEST to 0.8f, SHOUL_R to 0.2f), WHITE)
        return g.build()
    }

    private fun buildPack(): SkeletalMesh {
        val g = SkeletalMesh.Builder()
        box(g, 0f, 1.16f, -0.27f, 0.38f, 0.46f, 0.22f, listOf(SPINE to 0.6f, PELVIS to 0.4f), WHITE)
        box(g, 0f, 1.38f, -0.26f, 0.34f, 0.06f, 0.18f, listOf(SPINE to 0.8f, NECK to 0.2f), WHITE)
        return g.build()
    }
}
