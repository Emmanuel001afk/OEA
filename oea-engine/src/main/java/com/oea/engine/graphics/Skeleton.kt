package com.oea.engine.graphics

import com.oea.engine.math.Mat4
import com.oea.engine.math.MathX
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The humanoid skeleton behind OEA's skinned characters: 19 bones in a
 * standard game hierarchy (pelvis → spine → chest → neck → head, shoulders →
 * arms → forearms → hands, thighs → shins → feet).
 *
 * Joint angles are computed procedurally from a pose every frame — the same
 * walk-cycle math the primitive rig used, now driving real bones so the mesh
 * bends at actual joints instead of sliding primitives around. The bone
 * hierarchy is the whole point: a vertex near the elbow is weighted between
 * the forearm and upper-arm bones, so rotating the elbow bends the skin
 * with it.
 *
 * Bind pose (character local space, feet at y ≈ 0, facing +Z):
 * pelvis 0.90, spine 1.08, chest 1.36, neck 1.52, head 1.64, shoulders ±0.18
 * at 1.40, elbows 1.14, wrists 0.88, hips ±0.10 at 0.88, knees 0.44, ankles 0.02.
 */
class Skeleton {
    enum class Bone {
        PELVIS, SPINE, CHEST, NECK, HEAD,
        SHOULDER_L, ARM_L, FORE_L, HAND_L,
        SHOULDER_R, ARM_R, FORE_R, HAND_R,
        THIGH_L, SHIN_L, FOOT_L,
        THIGH_R, SHIN_R, FOOT_R,
    }

    val count: Int get() = Bone.entries.size

    private data class Def(val parent: Int, val x: Float, val y: Float, val z: Float)

    private val defs = listOf(
        Def(-1, 0f, 0.90f, 0f),       // PELVIS (root)
        Def(0, 0f, 0.18f, 0f),        // SPINE
        Def(1, 0f, 0.28f, 0f),        // CHEST
        Def(2, 0f, 0.16f, 0f),        // NECK
        Def(3, 0f, 0.12f, 0f),        // HEAD
        Def(2, 0.18f, 0.04f, 0f),     // SHOULDER_L
        Def(5, 0f, -0.26f, 0f),       // ARM_L (elbow)
        Def(6, 0f, -0.26f, 0f),       // FORE_L (wrist)
        Def(7, 0f, -0.07f, 0.01f),    // HAND_L
        Def(2, -0.18f, 0.04f, 0f),    // SHOULDER_R
        Def(9, 0f, -0.26f, 0f),       // ARM_R
        Def(10, 0f, -0.26f, 0f),      // FORE_R
        Def(11, 0f, -0.07f, 0.01f),   // HAND_R
        Def(0, 0.10f, -0.02f, 0f),    // THIGH_L
        Def(13, 0f, -0.44f, 0f),      // SHIN_L (knee)
        Def(14, 0f, -0.42f, 0f),      // FOOT_L (ankle)
        Def(0, -0.10f, -0.02f, 0f),   // THIGH_R
        Def(16, 0f, -0.44f, 0f),      // SHIN_R
        Def(17, 0f, -0.42f, 0f),      // FOOT_R
    )

    private val local = Array(count) { Mat4() }
    private val world = Array(count) { Mat4() }
    /** Packed bone matrices (count × 16), ready for the vertex shader. */
    val matrices = FloatArray(count * 16)

    private val trans = Mat4()
    private val rx = Mat4()
    private val ry = Mat4()
    private val rz = Mat4()
    private val rot = Mat4()
    private val root = Mat4()

    /**
     * Recomputes every bone from a pose.
     *
     * [phase] is the locomotion cycle in radians (advance by distance walked),
     * [speed] 0..1 (0 idle, 1 sprint), [lean] sideways roll -1..1, [aimHold]
     * and [aimPitch] the weapon-aim pose, [headYaw] the head-only glance,
     * [hipOffsetY] the vertical bob/crouch offset applied to the root.
     *
     * Rotation conventions: bones point along +Y (limbs point down). A
     * rotation about local X by `a` moves a down-pointing tip to
     * z = -L·sin(a) (backward for positive a) and an up-pointing top to
     * z = +L·sin(a) (forward for positive a).
     *
     * [reload] 0..1 (1 = just pressed): the arms drop to the weapon at waist
     * height — the reload pose.
     */
    fun update(
        phase: Float,
        speed: Float,
        airborne: Boolean,
        crouch: Boolean,
        lean: Float,
        aimHold: Boolean,
        aimPitch: Float,
        headYaw: Float,
        hipOffsetY: Float,
        reload: Float,
    ) {
        val s = speed.coerceIn(0f, 1f)
        val ph = phase

        // ---- root: pelvis translates with the bob/crouch offset
        root.setTranslation(0f, hipOffsetY, 0f)
        world[0].set(root.m)

        // ---- spine: forward lean with pace, deeper when crouching or falling
        val leanPitch = 0.10f * s + if (airborne) 0.18f else 0f + if (crouch) 0.30f else 0f
        val roll = lean * 0.35f + if (airborne) 0f else s * 0.07f * sin(ph)
        setLocal(1, 0f, 0.18f, 0f, leanPitch, 0f, -roll)

        // ---- chest: breathing sway rides the spine
        val breath = sin(ph * 2.2f) * 0.012f
        setLocal(2, 0f, 0.28f, 0f, 0f, 0f, breath)

        // ---- neck: head lags the aim slightly
        setLocal(3, 0f, 0.16f, 0f, -leanPitch * 0.3f, 0f, 0f)

        // ---- head: the glance turn plus a small aim follow
        setLocal(4, 0f, 0.12f, 0f, aimPitch * 0.3f, headYaw, 0f)

        // ---- arms
        for (side in intArrayOf(-1, 1)) {
            // side -1 = character's right (local -X), +1 = left (+X)
            val sho = if (side < 0) 9 else 5
            val arm = if (side < 0) 10 else 6
            val fore = if (side < 0) 11 else 7
            val hand = if (side < 0) 12 else 8
            var ua: Float; var el: Float
            if (aimHold) {
                ua = MathX.HALF_PI + aimPitch
                el = 0.18f
            } else if (airborne) {
                ua = MathX.PI_F * (0.42f + side * 0.12f)
                el = 0.35f
            } else if (s > 0.02f) {
                ua = s * 0.75f * sin(ph - side * MathX.PI_F)
                el = 0.55f + s * 0.35f * max(0f, sin(ph - side * MathX.PI_F + 1.2f))
            } else {
                ua = sin(ph * 0.24f + side) * 0.06f
                el = 0.22f
            }
            // Reload: the arms drop to the weapon at waist height.
            if (reload > 0f) {
                ua = MathX.lerp(ua, 0.45f, reload)
                el = MathX.lerp(el, 0.85f, reload)
            }
            setLocal(sho, side * 0.18f, 0.04f, 0f, 0f, 0f, 0f)
            setLocal(arm, 0f, -0.26f, 0f, -ua, 0f, 0f)
            setLocal(fore, 0f, -0.26f, 0f, el, 0f, 0f)
            setLocal(hand, 0f, -0.07f, 0.01f, 0f, 0f, 0f)
        }

        // ---- legs
        for (side in intArrayOf(-1, 1)) {
            val thigh = if (side < 0) 16 else 13
            val shin = if (side < 0) 17 else 14
            val foot = if (side < 0) 18 else 15
            val thighA: Float; val kneeB: Float
            if (airborne) {
                thighA = if (side < 0) 0.75f else -0.45f
                kneeB = if (side < 0) 0.35f else 1.15f
            } else if (crouch) {
                thighA = 0.95f
                kneeB = 1.05f
            } else if (s > 0.02f) {
                val lp = ph + side * MathX.PI_F
                thighA = s * 0.85f * sin(lp)
                kneeB = s * 1.05f * max(0f, cos(lp - 1.1f))
            } else {
                thighA = s * 0.85f * sin(0.2f * ph)
                kneeB = 0f
            }
            setLocal(thigh, side * 0.10f, -0.02f, 0f, -thighA, 0f, 0f)
            setLocal(shin, 0f, -0.44f, 0f, kneeB, 0f, 0f)
            setLocal(foot, 0f, -0.42f, 0f, 0f, 0f, 0f)
        }

        // ---- flatten the hierarchy into world matrices
        for (i in 1 until count) {
            val p = defs[i].parent
            world[i].mul(world[p], local[i])
        }
        // Pack for the shader.
        for (i in 0 until count) {
            System.arraycopy(world[i].m, 0, matrices, i * 16, 16)
        }
    }

    /** Local transform = translation(def) · Rz(rz) · Ry(ry) · Rx(rx). */
    private fun setLocal(i: Int, x: Float, y: Float, z: Float, rxA: Float, ryA: Float, rzA: Float) {
        rotX(rxA, rx)
        rotY(ryA, ry)
        rotZ(rzA, rz)
        ry.mul(rx)          // R = Y·X
        rz.mul(ry)          // R = Z·(Y·X)
        trans.setTranslation(x, y, z)
        local[i].set(trans.m)
        local[i].mul(rz)    // local = T·R
    }

    private fun rotX(a: Float, out: Mat4): Mat4 {
        val c = cos(a); val sn = sin(a)
        out.idt()
        out.m[5] = c; out.m[6] = sn
        out.m[9] = -sn; out.m[10] = c
        return out
    }

    private fun rotY(a: Float, out: Mat4): Mat4 {
        val c = cos(a); val sn = sin(a)
        out.idt()
        out.m[0] = c; out.m[2] = -sn
        out.m[8] = sn; out.m[10] = c
        return out
    }

    private fun rotZ(a: Float, out: Mat4): Mat4 {
        val c = cos(a); val sn = sin(a)
        out.idt()
        out.m[0] = c; out.m[1] = sn
        out.m[4] = -sn; out.m[5] = c
        return out
    }
}
