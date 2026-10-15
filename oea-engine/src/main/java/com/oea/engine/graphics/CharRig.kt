package com.oea.engine.graphics

import com.oea.engine.math.MathX
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * An articulated humanoid drawn entirely from smooth primitives: capsules for
 * limbs, spheres for joints and body parts, so characters read as *characters*
 * rather than stacks of crates.
 *
 * Proportions follow real anatomy loosely scaled to a stylised chibi look
 * (slightly oversized head, hands and feet — mobile screens are small, and the
 * personality has to survive the distance). All animation is procedural:
 *
 *  - running: a real run cycle with opposing arm/leg swing, knee-lift timing,
 *    hip bob and side-roll with speed;
 *  - airborne: legs scissor, arms open for balance;
 *  - crouch: hips drop, torso folds, both knees bend;
 *  - aiming: both arms raise toward a pitch (shooters);
 *  - idle: a gentle breathing sway so nobody stands like furniture.
 *
 * One rig is shared by every OEA game. Caller keeps a [Pose] per character and
 * draws with [draw]; joints are computed fresh each frame with no allocation.
 */
class CharRig(
    val body: Material,
    val pants: Material,
    val skin: Material,
    val shoes: Material,
    val hair: Material? = null,
    val ghost: Boolean = false,
) {

    /**
     * Mutable per-character animation state; the game updates a handful of
     * fields per frame — nothing else persists.
     */
    class Pose {
        /** Locomotion cycles in radians; advance by distance walked (≈ m·2.4). */
        var phase = 0f
        /** 0 standing still, 1 full sprint. Drives swing amplitude, bob and lean. */
        var speed = 0f
        var airborne = false
        var crouch = false
        /** Sideways body roll, -1 .. 1 (lane changes, banking). */
        var lean = 0f
        /** Arms hold a weapon/aim forward, pitched by [aimPitch]. */
        var aimHold = false
        var aimPitch = 0f
        /** Extra parts: joint spheres, belly blend, shoulder pads, hair cap. */
        var detail = true
    }

    // ---------------------------------------------------------------- layout (local space, facing +Z, metres)

    fun draw(r: Renderer3D, x: Float, y: Float, z: Float, yaw: Float, p: Pose, scale: Float = 1f) {
        val s = scale
        val sp = p.speed
        val ca = cos(yaw); val sa = sin(yaw)
        fun wx(lx: Float, lz: Float) = x + lx * ca + lz * sa
        fun wz(lx: Float, lz: Float) = z - lx * sa + lz * ca

        // Root position: hips sit lower when crouching, the run cycle adds a
        // double bounce, leaning banks the whole body sideways.
        val crouchK = if (p.crouch) 0.24f else 0f
        val bob = if (p.airborne || p.crouch) 0f else sp * 0.055f * abs(cos(p.phase))
        val hipY = y + (0.92f - crouchK) * s + bob * s
        val roll = p.lean * 0.35f + (if (p.airborne) 0f else sp * 0.07f * sin(p.phase))
        val pitch = 0.10f * sp + (if (p.airborne) 0.18f else 0f) + (if (p.crouch) 0.30f else 0f)

        // Leaning slides the whole upper body sideways.
        val leanX = roll * 0.30f * s

        // Spine: rotate the local torso chain by pitch around the hips.
        fun torso(offsetY: Float, offsetZ: Float): Float {
            val c = cos(pitch); val sn = sin(pitch)
            return hipY + offsetY * c - offsetZ * sn
        }
        fun torsoZ(offsetY: Float, offsetZ: Float): Float {
            val c = cos(pitch); val sn = sin(pitch)
            return offsetY * sn + offsetZ * c
        }

        val zz = torsoZ(0f, 0f)

        // Hips and belly: two overlapping spheres make a smooth waist.
        sph(r, pants, wx(0f, 0f + zz), hipY, wz(0f, 0f + zz), 0.30f * s)
        val bellyZ = torsoZ(0.20f, 0.02f)
        if (p.detail) sph(r, body, wx(leanX * 0.4f, bellyZ), torso(0.20f, 0.02f), wz(leanX * 0.4f, bellyZ), 0.25f * s)
        val chestZ = torsoZ(0.46f, 0.05f)
        sph(r, body, wx(leanX * 0.7f, chestZ), torso(0.46f, 0.05f), wz(leanX * 0.7f, chestZ), 0.30f * s)

        // Head: neck, face sphere, hair cap (slightly back and up), all riding
        // the chest so the head stays attached through the lean.
        val neckZ = torsoZ(0.70f, 0.06f)
        val neckY = torso(0.70f, 0.06f)
        sph(r, skin, wx(leanX * 0.85f, neckZ), neckY, wz(leanX * 0.85f, neckZ), 0.10f * s)
        val headZ = torsoZ(0.86f, 0.07f)
        val headY = torso(0.86f, 0.07f)
        sph(r, skin, wx(leanX, headZ), headY, wz(leanX, headZ), 0.185f * s)
        val hairM = hair
        if (hairM != null) {
            val capZ = headZ - 0.03f * s
            sph(r, hairM, wx(leanX, capZ), headY + 0.035f * s, wz(leanX, capZ), 0.175f * s)
        }

        // Legs: thigh -> knee -> shin -> ankle -> foot. Run cycle: hips swing in
        // opposition, the knee bends only backward, feet land flat-ish.
        for (side in intArrayOf(-1, 1)) {
            val hipX = side * 0.14f * s
            var thighA: Float
            var kneeB: Float
            when {
                p.airborne -> { thighA = if (side < 0) 0.75f else -0.45f; kneeB = if (side < 0) 0.35f else 1.15f }
                p.crouch -> { thighA = 0.95f; kneeB = 1.05f }
                sp > 0.02f -> {
                    val lp = p.phase + side * MathX.PI_F
                    thighA = sp * 0.85f * sin(lp)
                    kneeB = sp * 1.05f * max(0f, cos(lp - 1.1f))
                }
                else -> { thighA = sp * 0.85f * sin(0.2f * p.phase); kneeB = 0f }
            }
            val hipC = wx(hipX, 0f)
            val hipCz = wz(hipX, 0f)
            // Knee: thigh reaches forward/back from the hip.
            val t1 = sin(thighA); val t2 = cos(thighA)
            val knX = hipX; val knY = hipY - 0.44f * s * t2; val knZ = 0.44f * s * t1
            // Ankle: shin continues from the knee, bent back by kneeB.
            val shA = thighA - kneeB
            val s1 = sin(shA); val s2 = cos(shA)
            val anY = knY - 0.42f * s * s2; val anZ = knZ + 0.42f * s * s1
            val knwX = wx(knX, knZ); val knwZ = wz(knX, knZ)
            val anwX = wx(knX, anZ); val anwZ = wz(knX, anZ)
            limb(r, pants, hipC, hipY, hipCz, knwX, knY, knwZ, 0.115f * s)
            limb(r, pants, knwX, knY, knwZ, anwX, anY, anwZ, 0.098f * s)
            if (p.detail) sph(r, pants, knwX, knY, knwZ, 0.105f * s * 1.06f)
            // Foot: a squashed sphere pointed forward reads as a shoe from behind.
            val ftY = max(anY - 0.02f * s, y + 0.075f * s)
            val ftZ = anZ + 0.10f * s
            sphx(r, shoes, wx(knX, ftZ), ftY, wz(knX, ftZ), 0.105f * s, 0.075f * s, 0.20f * s, yaw)
        }

        // Arms: shoulders live on the chest and swing opposite the legs; aiming
        // raises both hands toward the pitch instead.
        for (side in intArrayOf(-1, 1)) {
            val shX = side * 0.335f * s + leanX * 0.75f
            val shZ = torsoZ(0.56f, 0.04f)
            val shY = torso(0.56f, 0.04f)
            var ua: Float  // upper-arm swing around X (forward +)
            var el: Float  // elbow bend
            if (p.aimHold) {
                ua = MathX.PI_F * 0.5f + p.aimPitch
                el = 0.18f
            } else if (p.airborne) {
                ua = MathX.PI_F * (0.42f + side * 0.12f)
                el = 0.35f
            } else if (sp > 0.02f) {
                ua = sp * 0.75f * sin(p.phase - side * MathX.PI_F) * 1f
                el = 0.55f + sp * 0.35f * max(0f, sin(p.phase - side * MathX.PI_F + 1.2f))
            } else {
                ua = sin(p.phase * 0.24f + side) * 0.06f // idle sway
                el = 0.22f
            }
            val shwX = wx(shX, shZ); val shwZ = wz(shX, shZ)
            val ua1 = sin(ua); val ua2 = cos(ua)
            val elY = shY - 0.30f * s * ua2; val elZ = shZ + 0.30f * s * ua1
            val fatA = ua + el
            val fa1 = sin(fatA); val fa2 = cos(fatA)
            val haY = elY - 0.26f * s * fa2; val haZ = elZ + 0.26f * s * fa1
            val elwZ = wz(shX, elZ); val elwX = wx(shX, elZ)
            val hawX = wx(shX, haZ); val hawZ = wz(shX, haZ)
            limb(r, body, shwX, shY, shwZ, elwX, elY, elwZ, 0.088f * s)
            val foreMat = if (p.detail) skin else body
            limb(r, foreMat, elwX, elY, elwZ, hawX, haY, hawZ, 0.076f * s)
            if (p.detail) {
                sph(r, body, elwX, elY, elwZ, 0.082f * s)
                sph(r, skin, hawX, haY, hawZ, 0.088f * s)
                // Shoulder pads hide the joint seam.
                sph(r, body, shwX, shY, shwZ, 0.095f * s)
            }
        }
    }

    // ---------------------------------------------------------------- primitive helpers

    private fun limb(r: Renderer3D, m: Material, ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, radius: Float) {
        r.drawLine(ax, ay, az, bx, by, bz, radius, m)
    }

    private fun sph(r: Renderer3D, m: Material, x: Float, y: Float, z: Float, radius: Float) {
        r.draw(Mesh.unitSphere, m, x, y, z, 0f, radius * 2f)
    }

    private fun sphx(r: Renderer3D, m: Material, x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, yaw: Float = 0f) {
        r.draw(Mesh.unitSphere, m, x, y, z, yaw, 0f, 0f, sx * 2f, sy * 2f, sz * 2f)
    }
}
