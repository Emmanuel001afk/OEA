package com.oea.engine.graphics

import com.oea.engine.math.MathX
import com.oea.engine.math.Mat4
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Shared humanoid rig for every OEA game — the drawing half of the Character
 * Creator. Two input layers:
 *
 *  1. **Legacy colours** (body/pants/skin/shoes materials) keep every existing
 *     game compiling and looking exactly as it did.
 *  2. **A full [CharSpec]** (gender, facial structure, body proportions, skin,
 *     hair, layered clothing) turns the rig into a live character: bump the
 *     spec's revision and the very next frame draws the new face, body and
 *     outfit. The Character Studio UI edits exactly this object.
 *
 * [CharFx] adds the world's physics on top: wind sways hair and coats, rain
 * wets the clothes (darker, glossier), dust settles on armour (pale film).
 * All of it is per-character state — nothing here is a canned animation.
 *
 * One rig is shared by every character; the caller keeps a [Pose] per character
 * and draws with [draw]. Joints are computed fresh each frame with no allocation.
 */
class CharRig(
    val body: Material,
    val pants: Material,
    val skin: Material,
    val shoes: Material,
    val hair: Material? = null,
    val ghost: Boolean = false,
    /** Second body colour: glove cuffs at the wrists and a boot cuff above the shoe. */
    val accent: Material? = null,
    /** 0 = short cap, 1 = ponytail, 2 = top bun. Only drawn when [hair] is set. */
    val hairStyle: Int = 0,
    /** Lighter frame: narrower shoulders, wider hips, softer neck. */
    val feminine: Boolean = false,
    /** Day pack drawn on the back (soldiers, runners with gear). */
    val backpack: Material? = null,
    /** Full character definition; when set, overrides the style params above. */
    val spec: CharSpec? = null,
    /** Armor vest material (game-owned; defaults to the outer layer). */
    val vest: Material? = null,
) {

    // ---- skinned pipeline: a real skeleton driving procedurally generated
    // body parts. Legacy rigs (no spec) get a default body; spec rigs rebuild
    // on revision change in [ensureForm].
    val skeleton = Skeleton()
    var human: HumanBody? = null
    private val tmp = Mat4()
    private val tmp2 = Mat4()

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
        /** Head-only turn within [-0.9, 0.9]: soldiers glance before bodies catch up. */
        var headYaw = 0f
        /** Extra parts: joint spheres, belly blend, shoulder pads, hair cap. */
        var detail = true
        /** 0..1 (1 = just pressed): the arms drop to the weapon — the reload pose. */
        var reload = 0f
    }

    // ---------------------------------------------------------------- spec plumbing

    private var form: CharForm? = null
    private var formRev = -1
    // Spec-mode private materials (clones, so weather can tint them freely).
    private var mSkin: Material = skin
    private var mHair: Material = hair ?: Material(color = 0xFF2E2118.toInt(), specular = 0.35f, shininess = 40f)
    private var mTop: Material = body
    private var mBottom: Material = pants
    private var mOuter: Material = body
    private var mShoes: Material = shoes
    private var mHead: Material = body
    private var mBack: Material = backpack ?: body
    private var mTrim: Material = accent ?: body
    private var mEye: Material = Material(color = 0xFF2A2118.toInt(), specular = 0.4f, shininess = 40f)
    private var mMouth: Material = Material(color = 0xFF9C5C52.toInt(), specular = 0.15f)
    private var mPaint: Material = Material(color = 0xFFB8452F.toInt(), specular = 0.1f)
    private var mFreckle: Material = Material(color = 0xFF9C6B4A.toInt(), specular = 0.05f)
    // Base values let rain/dust move up and down naturally instead of baking a
    // tint into the material forever as the weather changes.
    private var baseSkin = 0; private var baseTop = 0; private var baseBottom = 0; private var baseOuter = 0
    private var baseShoes = 0; private var baseHead = 0; private var baseBack = 0
    private var baseSkinSpec = 0.18f; private var baseTopSpec = 0.22f; private var baseBottomSpec = 0.18f
    private var baseOuterSpec = 0.26f; private var baseShoesSpec = 0.32f; private var baseHeadSpec = 0.25f; private var baseBackSpec = 0.22f
    private var baseSkinShine = 32f; private var baseTopShine = 32f; private var baseBottomShine = 32f
    private var baseOuterShine = 36f; private var baseShoesShine = 40f; private var baseHeadShine = 32f; private var baseBackShine = 32f
    private var weatherApplied = -1f

    // Legacy rigs (no spec) get a default body; the default skin carries the
    // SSS term on the skinned path so it reads as flesh, not plastic. Spec rigs
    // rebuild their materials in ensureForm instead.
    init {
        if (spec == null) {
            human = HumanBody(null, feminine, hairStyle)
            mSkin = Material(color = skin.color, texture = skin.texture, specular = skin.specular,
                shininess = skin.shininess, rim = skin.rim, subsurface = skin.color)
        }
    }

    /** Rebuilds private materials when the spec changes — the instant-apply path. */
    private fun ensureForm() {
        val sp = spec ?: return
        if (sp.revision == formRev && form != null) return
        formRev = sp.revision
        val f = sp.form()
        form = f
        mSkin = Material(color = f.skinColor, texture = CharTextures.forKey(f.skinTexture), specular = 0.18f, rim = 0.12f, subsurface = f.skinColor)
        mHair = Material(color = f.hairColor, texture = CharTextures.forKey("hair"), specular = 0.42f, shininess = 48f, rim = 0.18f)
        mTop = Material(color = f.topColor, texture = CharTextures.forKey(f.topTexture), specular = 0.22f, rim = 0.14f)
        mBottom = Material(color = f.bottomColor, texture = CharTextures.forKey(f.bottomTexture), specular = 0.18f, rim = 0.1f)
        mOuter = Material(color = f.outerColor, texture = CharTextures.forKey(f.outerTexture), specular = 0.26f, shininess = 36f, rim = 0.16f)
        mShoes = Material(color = f.shoeColor, texture = CharTextures.forKey(f.shoeTexture), specular = 0.32f, shininess = 40f)
        mHead = Material(color = f.headwearColor, texture = CharTextures.forKey(f.headwearTexture), specular = 0.25f, rim = 0.12f)
        mBack = Material(color = f.backColor, texture = CharTextures.forKey(f.backTexture), specular = 0.22f, rim = 0.12f)
        mTrim = Material(color = f.topTrim, texture = CharTextures.forKey("tactical"), specular = 0.2f)
        mEye = Material(color = f.eyeColor, specular = 0.55f, shininess = 60f)
        mMouth = Material(color = 0xFF9C5C52.toInt(), specular = 0.15f)
        mPaint = Material(color = f.paintColor, specular = 0.08f, unlit = true)
        mFreckle = Material(color = com.oea.engine.math.Colors.scale(f.skinColor, 0.72f), specular = 0.05f)
        baseSkin = mSkin.color; baseTop = mTop.color; baseBottom = mBottom.color; baseOuter = mOuter.color
        baseShoes = mShoes.color; baseHead = mHead.color; baseBack = mBack.color
        baseSkinSpec = mSkin.specular; baseTopSpec = mTop.specular; baseBottomSpec = mBottom.specular
        baseOuterSpec = mOuter.specular; baseShoesSpec = mShoes.specular; baseHeadSpec = mHead.specular; baseBackSpec = mBack.specular
        baseSkinShine = mSkin.shininess; baseTopShine = mTop.shininess; baseBottomShine = mBottom.shininess
        baseOuterShine = mOuter.shininess; baseShoesShine = mShoes.shininess; baseHeadShine = mHead.shininess; baseBackShine = mBack.shininess
        weatherApplied = -1f
        // Rebuild the skinned body whenever the spec changes (studio sliders).
        human = HumanBody(sp, f.feminine || feminine, f.hairStyle)
    }

    // ---------------------------------------------------------------- draw

    fun draw(r: Renderer3D, x: Float, y: Float, z: Float, yaw: Float, p: Pose, scale: Float = 1f, fx: CharFx? = null) {
        if (spec != null) ensureForm()
        val f = form
        // ---- resolved skeleton scales (legacy draws at the original 1.0)
        val height = if (f != null) f.heightScale else 1f
        val shoulder = if (f != null) f.shoulderScale else if (feminine) 0.92f else 1f
        val hips = if (f != null) f.hipScale else if (feminine) 1.1f else 1f
        val limbK = if (f != null) f.limbScale else 1f
        val fem = f?.feminine ?: feminine
        val s = scale * height
        val sp = p.speed
        val ca = cos(yaw); val sa = sin(yaw)

        // Weather: wet clothes darken and gloss; dust settles pale. Private
        // materials absorb it, the shared legacy ones only tint.
        val wet = fx?.wetness ?: 0f
        val dust = fx?.dust ?: 0f
        applyWeather(fx)
        val wetTint = wetTintFor(wet)

        fun wx(lx: Float, lz: Float) = x + lx * ca + lz * sa
        fun wz(lx: Float, lz: Float) = z - lx * sa + lz * ca

        // Root position: hips sit lower when crouching, the run cycle adds a
        // double bounce, leaning banks the whole body sideways.
        val crouchK = if (p.crouch) 0.24f else 0f
        val bob = if (p.airborne || p.crouch) 0f else sp * 0.035f * abs(cos(p.phase))
        val hipY = y + (0.92f - crouchK) * s + bob * s
        val roll = p.lean * 0.35f + (if (p.airborne) 0f else sp * 0.04f * sin(p.phase))
        val pitch = 0.10f * sp + (if (p.airborne) 0.18f else 0f) + (if (p.crouch) 0.30f else 0f)
        val leanX = roll * 0.30f * s

        fun torso(offsetY: Float, offsetZ: Float): Float {
            val c = cos(pitch); val sn = sin(pitch)
            return hipY + offsetY * c - offsetZ * sn
        }
        fun torsoZ(offsetY: Float, offsetZ: Float): Float {
            val c = cos(pitch); val sn = sin(pitch)
            return offsetY * sn + offsetZ * c
        }

        val zz = torsoZ(0f, 0f)

        // ------------------------------------------------- hips, waist, chest
        val hipR = 0.30f * hips * limbK
        sphTinted(r, mBottom, wx(0f, zz), hipY, wz(0f, zz), hipR * s, wetTint)
        // Bottom shell over the hips (skirt/cargo bulk).
        if (f != null && f.bottom != "skirt") {
            sphxTinted(r, mBottom, wx(0f, zz), hipY - 0.02f * s, wz(0f, zz), hipR * 1.08f * s, hipR * 0.92f * s, hipR * 1.02f * s, yaw, wetTint)
        } else if (f != null) {
            // Skirt: a flared cone of fabric that sways with wind and motion.
            val swayX = (fx?.windX ?: 0f) * 0.06f + sin(p.phase) * 0.02f * sp
            sphxTinted(r, mBottom, wx(swayX * s, zz), hipY - 0.12f * s, wz(swayX * s, zz), hipR * 1.32f * s, 0.17f * s, hipR * 1.3f * s, yaw, wetTint)
        }
        val bellyZ = torsoZ(0.20f, 0.02f)
        if (p.detail) sphTinted(r, mTop, wx(leanX * 0.4f, bellyZ), torso(0.20f, 0.02f), wz(leanX * 0.4f, bellyZ), 0.25f * s * limbK, wetTint)
        val chestZ = torsoZ(0.46f, 0.05f)
        val chestR = (if (fem) 0.282f else 0.30f) * limbK * (if (f != null) f.chestDepth else 1f)
        sphTinted(r, mTop, wx(leanX * 0.7f, chestZ), torso(0.46f, 0.05f), wz(leanX * 0.7f, chestZ), chestR * s, wetTint)
        // Waist shaping band (feminine or narrow waist).
        val waistK = if (f != null) f.waistScale else 1f
        if (waistK < 0.97f || fem) {
            sphxTinted(r, mTop, wx(leanX * 0.55f, torsoZ(0.32f, 0.03f)), torso(0.32f, 0.03f), wz(leanX * 0.55f, torsoZ(0.32f, 0.03f)),
                0.252f * s * waistK, 0.10f * s, 0.238f * s * waistK, yaw, wetTint)
        }

        // Clothing: top shell slightly proud of the chest (shirt/hoodie).
        val topPart = f?.top ?: "tee"
        if (f != null && topPart != "tank") {
            val fit = if (topPart == "hoodie_t") 1.12f else if (topPart == "tactical") 1.08f else 1.05f
            sphxTinted(r, mTop, wx(leanX * 0.7f, chestZ), torso(0.44f, 0.05f), wz(leanX * 0.7f, chestZ),
                chestR * fit * s, 0.33f * s, chestR * fit * 0.92f * s, yaw, wetTint)
        }
        // Outer layer: jacket/hoodie/coat/vest shell, with coat tails.
        if (f != null && f.outer != "none") {
            val bulk = when (f.outer) {
                "jacket" -> 1.12f; "hoodie" -> 1.16f; "vest" -> 1.08f; "coat" -> 1.18f; else -> 1.1f
            }
            sphxTinted(r, mOuter, wx(leanX * 0.7f, chestZ), torso(0.42f, 0.05f), wz(leanX * 0.7f, chestZ),
                chestR * bulk * s, 0.36f * s, chestR * bulk * 0.95f * s, yaw, wetTint)
            sphxTinted(r, mOuter, wx(leanX * 0.4f, bellyZ), torso(0.16f, 0.02f), wz(leanX * 0.4f, bellyZ),
                0.28f * bulk * limbK * s, 0.16f * s, 0.26f * bulk * limbK * s, yaw, wetTint)
            if (f.outer == "coat") {
                val sway = (fx?.windZ ?: 0f) * 0.05f
                sphxTinted(r, mOuter, wx(leanX * 0.3f, torsoZ(-0.12f, -0.06f) + sway), torso(-0.12f, -0.06f), wz(leanX * 0.3f, torsoZ(-0.12f, -0.06f) + sway),
                    0.32f * s, 0.22f * s, 0.28f * s, yaw, wetTint)
            }
            if (f.outer == "hoodie") {
                // Hood resting on the upper back.
                sphxTinted(r, mOuter, wx(leanX * 0.85f, torsoZ(0.62f, -0.16f)), torso(0.62f, -0.16f), wz(leanX * 0.85f, torsoZ(0.62f, -0.16f)),
                    0.16f * s, 0.11f * s, 0.10f * s, yaw, wetTint)
            }
        }

        // ------------------------------------------------- neck + head
        val neckZ = torsoZ(0.70f, 0.06f)
        val neckY = torso(0.70f, 0.06f)
        val neckK = if (f != null) f.neckScale else 1f
        sphTinted(r, mSkin, wx(leanX * 0.85f, neckZ), neckY, wz(leanX * 0.85f, neckZ), (if (fem) 0.092f else 0.10f) * s * neckK, wetTint)
        val headZ = torsoZ(0.86f, 0.07f)
        val headY = torso(0.86f, 0.07f)
        val hdYaw = yaw + p.headYaw
        val hca = cos(hdYaw - yaw); val hsa = sin(hdYaw - yaw)
        val hswing = hsa * 0.05f * s

        // Skull: width/length scales ride the face spec.
        val fw = if (f != null) f.faceWidth else 1f
        val fl = if (f != null) f.faceLength else 1f
        sphxTinted(r, mSkin, wx(leanX + hswing, headZ), headY, wz(leanX + hswing, headZ),
            0.185f * s * fw, 0.195f * s * fl, 0.185f * s, hdYaw, wetTint)

        if (p.detail && !ghost) {
            val jawW = if (f != null) f.jawWidth else 1f
            val jawD = if (f != null) f.jawDepth else 1f
            // Jaw + chin: the lower face mass, parameterised.
            sphxTinted(r, mSkin, wx(leanX + hswing, headZ + 0.03f * s), headY - 0.10f * s, wz(leanX + hswing, headZ + 0.03f * s),
                0.150f * s * jawW, 0.105f * s * jawD, 0.150f * s, hdYaw, wetTint)
            val chinL = if (f != null) f.chinLength else 1f
            val chinW = if (f != null) f.chinWidth else 1f
            sphxTinted(r, mSkin, wx(leanX + hswing, headZ + 0.11f * s), headY - 0.128f * s * chinL, wz(leanX + hswing, headZ + 0.11f * s),
                0.062f * s * chinW, 0.052f * s, 0.05f * s, hdYaw, wetTint)
            // Cheekbones.
            val cb = if (f != null) f.cheekbones else 1f
            for (side in intArrayOf(-1, 1)) {
                fun facePt(hx: Float, hz: Float): Pair<Float, Float> {
                    val lx = leanX + hswing + hx * hca + hz * hsa
                    val lz = headZ - hx * hsa + hz * hca
                    return wx(lx, lz) to wz(lx, lz)
                }
                val (cbx, cbz) = facePt(side * 0.118f * s * cb, 0.075f * s)
                sphxTinted(r, mSkin, cbx, headY + 0.005f * s, cbz, 0.055f * s * cb, 0.05f * s, 0.05f * s, hdYaw, wetTint)
            }
            // Ears on the sides of the skull.
            val ear = if (f != null) f.earSize else 1f
            for (side in intArrayOf(-1, 1)) {
                val ex = leanX + hswing + side * 0.185f * s * fw * (hca)
                val ez = headZ - side * 0.185f * s * fw * (hsa)
                sphxTinted(r, mSkin, wx(ex, ez), headY - 0.01f * s, wz(ex, ez), 0.042f * s * ear, 0.055f * s * ear, 0.032f * s * ear, hdYaw, wetTint)
            }
            // A real face: brows over dark eyes, a nose, a mouth. All ride the
            // head yaw so a glance keeps its expression attached.
            fun facePt(hx: Float, hz: Float): Pair<Float, Float> {
                val lx = leanX + hswing + hx * hca + hz * hsa
                val lz = headZ - hx * hsa + hz * hca
                return wx(lx, lz) to wz(lx, lz)
            }
            val eyeS = if (f != null) f.eyeSize else 1f
            val eyeSp = if (f != null) f.eyeSpacing else 1f
            val eyeD = if (f != null) f.eyeDepth else 1f
            val browR = if (f != null) f.browRidge else 1f
            val browA = if (f != null) f.browAngle else 0f
            for (side in intArrayOf(-1, 1)) {
                val (ex2, ez2) = facePt(side * 0.072f * s * eyeSp, (0.125f - (1f - eyeD) * 0.012f) * s)
                sphxTinted(r, mEye, ex2, headY + 0.030f * s, ez2, 0.036f * s * eyeS, 0.046f * s * eyeS, 0.028f * s * eyeS, hdYaw, wetTint)
                val (bx, bz) = facePt(side * 0.075f * s * eyeSp, 0.118f * s)
                sphxTinted(r, mHair, bx, headY + (0.082f + browA * 0.012f * side) * s, bz,
                    0.048f * s * browR, 0.016f * s * browR, 0.022f * s * browR, hdYaw, wetTint)
            }
            // Nose: bridge + tip, parameterised width and length.
            val nW = if (f != null) f.noseWidth else 1f
            val nL = if (f != null) f.noseLength else 1f
            val nB = if (f != null) f.noseBridge else 1f
            val (nx, nz) = facePt(0f, 0.155f * s)
            sphxTinted(r, mSkin, nx, headY - 0.005f * s * nL, nz, 0.034f * s * nW, 0.048f * s * nL, 0.040f * s * nB, hdYaw, wetTint)
            val (n2x, n2z) = facePt(0f, 0.142f * s)
            sphxTinted(r, mSkin, n2x, headY + 0.030f * s, n2z, 0.022f * s * nB, 0.038f * s, 0.028f * s, hdYaw, wetTint)
            // Mouth.
            val mW = if (f != null) f.mouthWidth else 1f
            val mL = if (f != null) f.lipFullness else 1f
            val (mx, mz) = facePt(0f, 0.150f * s)
            sphxTinted(r, mMouth, mx, headY - 0.088f * s, mz, 0.052f * s * mW, 0.016f * s * mL, 0.020f * s, hdYaw, wetTint)
            // Markings: freckles, a scar, war paint.
            if (f != null && f.freckles > 0.05f) {
                for (i in 0 until 6) {
                    val side = if (i % 2 == 0) -1 else 1
                    val (fx2, fz2) = facePt(side * (0.05f + (i % 3) * 0.032f) * s, 0.128f * s)
                    sphxTinted(r, mFreckle, fx2, headY - (0.02f + (i % 3) * 0.018f) * s, fz2,
                        0.011f * s, 0.008f * s, 0.006f * s, hdYaw, 0xFFFFFFFF.toInt())
                }
            }
            if (f != null && f.scar > 0.05f) {
                val (sx2, sz2) = facePt(-0.09f * s, 0.122f * s)
                sphxTinted(r, mMouth, sx2, headY + 0.01f * s, sz2, 0.012f * s, 0.07f * s * f.scar, 0.008f * s, hdYaw, wetTint)
            }
            if (f != null && f.warpaint > 0.05f) {
                for (side in intArrayOf(-1, 1)) {
                    val (px2, pz2) = facePt(side * 0.105f * s, 0.112f * s)
                    sphxTinted(r, mPaint, px2, headY - 0.01f * s, pz2, 0.022f * s, 0.075f * s * f.warpaint, 0.012f * s, hdYaw, 0xFFFFFFFF.toInt())
                }
            }
            // Facial hair rides the jaw.
            if (f != null && f.facialHair > 0) {
                val beardK = if (f.facialHair == 2) 1f else 0.55f
                sphxTinted(r, mHair, wx(leanX + hswing, headZ + 0.05f * s), headY - 0.115f * s, wz(leanX + hswing, headZ + 0.05f * s),
                    0.135f * s * jawW * beardK, 0.085f * s * beardK, 0.125f * s * beardK, hdYaw, wetTint)
            }
        }
        // Hair with wind sway.
        val hairM = if (spec != null) mHair else hair
        if (hairM != null) {
            val style = if (f != null) f.hairStyle else hairStyle
            val windX = (fx?.windX ?: 0f) * 0.035f * s
            val windZ = (fx?.windZ ?: 0f) * 0.035f * s
            val capZ = headZ - 0.03f * s
            sph(r, hairM, wx(leanX, capZ), headY + 0.035f * s, wz(leanX, capZ), 0.175f * s)
            when (style) {
                1 -> {
                    // Ponytail: a chain of shrinking spheres, swung by wind and pace.
                    val swing = sin(p.phase * 1.1f) * 0.02f * sp + windX
                    sphx(r, hairM, wx(leanX + swing * 0.4f, headZ - 0.17f * s), headY - 0.02f * s, wz(leanX + swing * 0.4f, headZ - 0.17f * s), 0.075f * s, 0.10f * s, 0.16f * s, hdYaw)
                    sphx(r, hairM, wx(leanX + swing, headZ - 0.21f * s + windZ), headY - 0.16f * s, wz(leanX + swing, headZ - 0.21f * s + windZ), 0.058f * s, 0.11f * s, 0.11f * s, hdYaw)
                }
                2 -> sph(r, hairM, wx(leanX, headZ - 0.09f * s), headY + 0.21f * s, wz(leanX, headZ - 0.09f * s), 0.105f * s)
                3 -> {
                    // Long hair: side curtains + a back fall that leans in the wind.
                    for (side in intArrayOf(-1, 1)) {
                        sphx(r, hairM, wx(leanX + side * 0.145f * s, headZ - 0.04f * s), headY - 0.10f * s, wz(leanX + side * 0.145f * s, headZ - 0.04f * s), 0.052f * s, 0.16f * s, 0.10f * s, hdYaw)
                    }
                    sphx(r, hairM, wx(leanX + windX, headZ - 0.16f * s + windZ), headY - 0.13f * s, wz(leanX + windX, headZ - 0.16f * s + windZ), 0.135f * s, 0.22f * s, 0.085f * s, hdYaw)
                }
                4 -> { /* buzz: the cap alone reads as cropped hair */ }
                5 -> {
                    // Mohawk: a ridge of shrinking tufts along the skull.
                    for (i in 0 until 4) {
                        val t = i / 3f
                        sph(r, hairM, wx(leanX, headZ - (0.11f - t * 0.14f) * s), headY + (0.16f + 0.055f * sin(t * MathX.PI_F)) * s, wz(leanX, headZ - (0.11f - t * 0.14f) * s), (0.062f - t * 0.012f) * s)
                    }
                }
            }
        }

        // Headwear.
        if (f != null && f.headwear != "none") {
            when (f.headwear) {
                "cap" -> {
                    sphxTinted(r, mHead, wx(leanX + hswing, headZ - 0.01f * s), headY + 0.115f * s, wz(leanX + hswing, headZ - 0.01f * s), 0.175f * s, 0.085f * s, 0.175f * s, hdYaw, wetTint)
                    sphxTinted(r, mHead, wx(leanX + hswing, headZ + 0.16f * s), headY + 0.09f * s, wz(leanX + hswing, headZ + 0.16f * s), 0.11f * s, 0.022f * s, 0.10f * s, hdYaw, wetTint)
                }
                "beanie" -> sphxTinted(r, mHead, wx(leanX + hswing, headZ - 0.02f * s), headY + 0.10f * s, wz(leanX + hswing, headZ - 0.02f * s), 0.185f * s, 0.115f * s, 0.185f * s, hdYaw, wetTint)
                "helmet" -> {
                    sphxTinted(r, mHead, wx(leanX + hswing, headZ - 0.015f * s), headY + 0.085f * s, wz(leanX + hswing, headZ - 0.015f * s), 0.205f * s, 0.155f * s, 0.205f * s, hdYaw, wetTint)
                    sphxTinted(r, mHead, wx(leanX + hswing, headZ + 0.02f * s), headY + 0.015f * s, wz(leanX + hswing, headZ + 0.02f * s), 0.21f * s, 0.035f * s, 0.21f * s, hdYaw, wetTint)
                }
                "hijab" -> {
                    sphxTinted(r, mHead, wx(leanX + hswing, headZ - 0.04f * s), headY + 0.02f * s, wz(leanX + hswing, headZ - 0.04f * s), 0.20f * s, 0.21f * s, 0.19f * s, hdYaw, wetTint)
                    sphxTinted(r, mHead, wx(leanX + hswing * 1.4f, headZ - 0.12f * s), headY - 0.17f * s, wz(leanX + hswing * 1.4f, headZ - 0.12f * s), 0.165f * s, 0.18f * s, 0.12f * s, hdYaw, wetTint)
                }
            }
        }

        // Waist seam: a flattened band hides the pant/shirt colour switch (legacy).
        if (accent != null && f == null) {
            sphx(r, accent!!, wx(leanX * 0.5f, torsoZ(0.14f, 0.02f)), torso(0.14f, 0.02f), wz(leanX * 0.5f, torsoZ(0.14f, 0.02f)), 0.27f * s, 0.09f * s, 0.24f * s, yaw)
        }

        // Day pack / back item.
        val packM = if (spec != null) mBack else backpack
        val packOn = if (f != null) f.back != "none" else backpack != null
        if (packOn && packM != null && p.detail) {
            val pkZ = torsoZ(0.42f, -0.26f)
            if (f != null && f.back == "quiver") {
                sphx(r, packM, wx(leanX * 0.6f, pkZ), torso(0.42f, -0.26f), wz(leanX * 0.6f, pkZ), 0.10f * s, 0.36f * s, 0.10f * s, yaw)
            } else {
                sphx(r, packM, wx(leanX * 0.6f, pkZ), torso(0.42f, -0.26f), wz(leanX * 0.6f, pkZ), 0.30f * s, 0.38f * s, 0.17f * s, yaw)
                sphx(r, packM, wx(leanX * 0.6f, torsoZ(0.30f, -0.32f)), torso(0.30f, -0.32f), wz(leanX * 0.6f, torsoZ(0.30f, -0.32f)), 0.22f * s, 0.16f * s, 0.10f * s, yaw)
            }
        }

        // ------------------------------------------------- legs
        for (side in intArrayOf(-1, 1)) {
            val hipX = side * (if (fem) 0.152f else 0.14f) * s * hips
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
            val t1 = sin(thighA); val t2 = cos(thighA)
            val knX = hipX; val knY = hipY - 0.44f * s * t2; val knZ = 0.44f * s * t1
            val shA = thighA - kneeB
            val s1 = sin(shA); val s2 = cos(shA)
            val anY = knY - 0.42f * s * s2; val anZ = knZ + 0.42f * s * s1
            val knwX = wx(knX, knZ); val knwZ = wz(knX, knZ)
            val anwX = wx(knX, anZ); val anwZ = wz(knX, anZ)
            val legR = 0.115f * limbK
            limbTinted(r, mBottom, hipC, hipY, hipCz, knwX, knY, knwZ, legR * s, wetTint)
            // Shorts end above the knee: shin is bare skin then.
            val shortLegs = f != null && (f.bottom == "shorts" || f.bottom == "skirt")
            val shinM = if (shortLegs) mSkin else mBottom
            limbTinted(r, shinM, knwX, knY, knwZ, anwX, anY, anwZ, legR * 0.85f * s, wetTint)
            if (p.detail) sphTinted(r, mBottom, knwX, knY, knwZ, legR * 0.92f * s * 1.06f, wetTint)
            // Foot: a squashed sphere pointed forward reads as a shoe from behind.
            val ftY = max(anY - 0.02f * s, y + 0.075f * s)
            val ftZ = anZ + 0.10f * s
            val shoeBulk = if (f != null && f.footwear == "boots") 1.12f else 1f
            sphxTinted(r, mShoes, wx(knX, ftZ), ftY, wz(knX, ftZ), 0.105f * s * shoeBulk, 0.075f * s * shoeBulk, 0.20f * s * shoeBulk, yaw, wetTint)
            if (f != null && f.footwear == "boots") {
                // Boot cuff rises over the ankle.
                sphTinted(r, mShoes, wx(knX, anZ), max(anY + 0.03f * s, y + 0.12f * s), wz(knX, anZ), 0.098f * s, wetTint)
            }
            val acM2 = accent
            if (p.detail && acM2 != null && f == null) {
                val cuY = anY - 0.045f * s
                sph(r, acM2, wx(knX, anZ + 0.015f * s), max(cuY, y + 0.11f * s), wz(knX, anZ + 0.015f * s), 0.095f * s)
            }
        }

        // ------------------------------------------------- arms
        for (side in intArrayOf(-1, 1)) {
            val shX = side * (if (fem) 0.302f else 0.335f) * s * shoulder + leanX * 0.75f
            val shZ = torsoZ(0.56f, 0.04f)
            val shY = torso(0.56f, 0.04f)
            var ua: Float
            var el: Float
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
                ua = sin(p.phase * 0.24f + side) * 0.06f
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
            val armR = 0.088f * limbK
            // Sleeve coverage decides how much arm is fabric vs skin.
            val sleeve = if (f != null) {
                val base = when (f.top) {
                    "tank" -> 0.15f; "tee" -> 0.5f; else -> 0.92f
                }
                val over = if (f.outer != "none" && f.outer != "vest") 1f else 0f
                max(base, over)
            } else 1f
            val sleeveM = if (f != null && f.outer != "none" && f.outer != "vest") mOuter else mTop
            if (sleeve > 0.7f) {
                limbTinted(r, sleeveM, shwX, shY, shwZ, elwX, elY, elwZ, armR * s, wetTint)
                limbTinted(r, sleeveM, elwX, elY, elwZ, hawX, haY, hawZ, armR * 0.86f * s, wetTint)
            } else if (sleeve > 0.3f) {
                limbTinted(r, sleeveM, shwX, shY, shwZ, elwX, elY, elwZ, armR * s, wetTint)
                limbTinted(r, mSkin, elwX, elY, elwZ, hawX, haY, hawZ, armR * 0.86f * s, wetTint)
            } else {
                limbTinted(r, mSkin, shwX, shY, shwZ, elwX, elY, elwZ, armR * s, wetTint)
                limbTinted(r, mSkin, elwX, elY, elwZ, hawX, haY, hawZ, armR * 0.86f * s, wetTint)
            }
            if (p.detail) {
                sphTinted(r, if (sleeve > 0.3f) sleeveM else mSkin, elwX, elY, elwZ, armR * 0.92f * s, wetTint)
                sphTinted(r, mSkin, hawX, haY, hawZ, armR * 1.02f * s, wetTint)
                sphTinted(r, if (sleeve > 0.3f) sleeveM else mSkin, shwX, shY, shwZ, armR * 1.08f * s, wetTint)
            }
            // Palm: a squashed ellipsoid reaching past the wrist, gloves cuff.
            val plY = haY - 0.10f * s * fa2
            val plZ = haZ + 0.10f * s * fa1
            sphxTinted(r, mSkin, wx(shX, plZ), plY, wz(shX, plZ), 0.052f * s, 0.088f * s, 0.062f * s, yaw, wetTint)
            val acM = accent
            if (p.detail && acM != null && f == null) {
                val cuY = haY - 0.015f * s * fa2
                val cuZ = haZ + 0.015f * s * fa1
                sph(r, acM, wx(shX, cuZ), cuY, wz(shX, cuZ), 0.078f * s)
            }
        }
    }

    // ---------------------------------------------------------------- skinned pipeline

    /** Weather tint for the skinned path (shared with the legacy primitive draw). */
    private fun applyWeather(fx: CharFx?) {
        val wet = fx?.wetness ?: 0f
        val dust = fx?.dust ?: 0f
        if (spec != null && form != null && abs(wet + dust - weatherApplied) > 0.03f) {
            weatherApplied = wet + dust
            val gloss = 1f + wet * 1.6f
            val dustCol = 0xFFC9B9A0.toInt()
            mSkin.color = mixTint(baseSkin, dustCol, dust * 0.12f); mSkin.specular = (baseSkinSpec * (1f + wet * 1.2f)).coerceAtMost(1f); mSkin.shininess = (baseSkinShine * gloss).coerceAtMost(160f)
            mTop.color = mixTint(baseTop, dustCol, dust * 0.38f); mTop.specular = (baseTopSpec * (1f + wet * 1.2f)).coerceAtMost(1f); mTop.shininess = (baseTopShine * gloss).coerceAtMost(160f)
            mBottom.color = mixTint(baseBottom, dustCol, dust * 0.32f); mBottom.specular = (baseBottomSpec * (1f + wet * 1.2f)).coerceAtMost(1f); mBottom.shininess = (baseBottomShine * gloss).coerceAtMost(160f)
            mOuter.color = mixTint(baseOuter, dustCol, dust * 0.42f); mOuter.specular = (baseOuterSpec * (1f + wet * 1.2f)).coerceAtMost(1f); mOuter.shininess = (baseOuterShine * gloss).coerceAtMost(160f)
            mShoes.color = mixTint(baseShoes, dustCol, dust * 0.3f); mShoes.specular = (baseShoesSpec * (1f + wet * 1.2f)).coerceAtMost(1f); mShoes.shininess = (baseShoesShine * gloss).coerceAtMost(160f)
            mHead.color = mixTint(baseHead, dustCol, dust * 0.35f); mHead.specular = (baseHeadSpec * (1f + wet * 1.2f)).coerceAtMost(1f); mHead.shininess = (baseHeadShine * gloss).coerceAtMost(160f)
            mBack.color = mixTint(baseBack, dustCol, dust * 0.45f); mBack.specular = (baseBackSpec * (1f + wet * 1.2f)).coerceAtMost(1f); mBack.shininess = (baseBackShine * gloss).coerceAtMost(160f)
        }
    }

    /**
     * Draws the character with the skinned pipeline: one skeleton drives the
     * whole body — every joint bends, the mesh is skinned in the shader.
     *
     * [vest]/[helmet]/[pack] gate the armor/headwear/pack meshes; the game
     * decides from kit state. [vestMat] overrides the armor material (the
     * game owns it), falling back to the outer layer.
     */
    fun drawSkinned(
        r: Renderer3D, x: Float, y: Float, z: Float, yaw: Float, p: Pose, scale: Float = 1f, fx: CharFx? = null,
        vest: Boolean = false, helmet: Boolean = false, pack: Boolean = false, vestMat: Material? = null,
        lod: Int = 0,
    ) {
        val h = human ?: return
        if (spec != null) ensureForm()
        applyWeather(fx)
        // Pose → skeleton. The pelvis rides the crouch/bob offset in local units;
        // the draw scale stretches it with the body.
        val crouchK = if (p.crouch) 0.22f else 0f
        val bob = if (p.airborne || p.crouch) 0f else p.speed * 0.035f * abs(cos(p.phase))
        skeleton.update(p.phase, p.speed, p.airborne, p.crouch, p.lean, p.aimHold, p.aimPitch, p.headYaw, bob - crouchK, p.reload)
        tmp.setPosYaw(x, y, z, yaw, scale)
        val bones = skeleton.matrices
        r.drawSkinned(h.body, mSkin, bones, tmp)
        r.drawSkinned(h.shirt, mTop, bones, tmp)
        if (lod < 2 && spec != null && form?.outer != "none") r.drawSkinned(h.outer, mOuter, bones, windTmp(x, y, z, yaw, scale, fx))
        r.drawSkinned(h.pants, mBottom, bones, tmp)
        if (lod < 2 && (spec != null || hair != null)) r.drawSkinned(h.hair, mHair, bones, windTmp(x, y, z, yaw, scale, fx))
        r.drawSkinned(h.shoes, mShoes, bones, tmp)
        if (lod < 1 && vest) r.drawSkinned(h.vest, vestMat ?: mOuter, bones, tmp)
        if (lod < 1 && helmet) r.drawSkinned(h.headwear, mHead, bones, tmp)
        if (lod < 1 && pack) r.drawSkinned(h.pack, mBack, bones, tmp)
        // Eyes: own skinned mesh on the HEAD bone; the blink squashes it in Y.
        // The mesh is centered on the eye line (y = 0), so it draws at head height.
        // The small extra yaw/pitch makes the eyes glance toward the aim.
        if (lod < 1 && h.face.vertexCount > 0) {
            tmp2.setTRS(x, y + 1.632f * scale, z, yaw + p.headYaw * 0.08f, p.aimPitch * 0.12f, 0f, scale, scale * blinkK, scale)
            r.drawSkinned(h.face, mEye, bones, tmp2)
        }
        // Brows: own mesh, flattened in Y with aiming/running tension (furrow).
        if (lod < 1 && h.brows.vertexCount > 0) {
            val tension = (0.25f + abs(p.aimPitch) * 0.5f + p.speed * 0.35f + if (p.crouch) 0.15f else 0f).coerceAtMost(1f)
            tmp2.setTRS(x, y + 1.632f * scale, z, yaw, 0f, 0f, scale, scale * (1f - 0.5f * tension), scale)
            r.drawSkinned(h.brows, mHair, bones, tmp2)
        }
    }

    /** Hair and coat shells sway with the wind: a hair-trigger rotation about the
     * character's feet — small angles, so the head barely moves. */
    private fun windTmp(x: Float, y: Float, z: Float, yaw: Float, scale: Float, fx: CharFx?): Mat4 {
        if (fx != null && (fx.windX != 0f || fx.windZ != 0f)) {
            tmp2.setTRS(x, y, z, yaw + fx.windX * 0.01f, -fx.windZ * 0.015f, 0f, scale, scale, scale)
        } else tmp2.setPosYaw(x, y, z, yaw, scale)
        return tmp2
    }

    // ---------------------------------------------------------------- face: blink

    private val rnd = java.util.Random()
    private var blinkNext = 0f
    private var blinkUntil = 0f
    /** 1 = eyes open, ~0.08 = blinking. Drives the face mesh's Y scale. */
    var blinkK = 1f

    /**
     * Advances the blink schedule from absolute game time (seconds). Blinks
     * every 2–6 s, each blink is a 0.14 s smooth close/open. Call once per
     * frame per rig; the draw reads [blinkK].
     */
    fun faceUpdate(t: Float) {
        if (t >= blinkNext) {
            blinkUntil = t + 0.05f
            blinkNext = t + 2.0f + rnd.nextFloat() * 4.0f
        }
        blinkK = if (t < blinkUntil + 0.14f) {
            val k = (t - blinkUntil) / 0.14f  // 0..1 across the blink
            1f - 0.92f * (1f - abs(k * 2f - 1f))  // smooth close and open
        } else 1f
    }

    // ---------------------------------------------------------------- primitive helpers

    private fun mixTint(a: Int, b: Int, t: Float): Int {
        if (t <= 0f) return a
        val k = t.coerceAtMost(1f)
        val ar = (a shr 16) and 0xFF; val ag = (a shr 8) and 0xFF; val ab = a and 0xFF
        val br = (b shr 16) and 0xFF; val bg = (b shr 8) and 0xFF; val bb = b and 0xFF
        val rr = (ar + (br - ar) * k).toInt(); val rg = (ag + (bg - ag) * k).toInt(); val rb = (ab + (bb - ab) * k).toInt()
        return (a and 0xFF000000.toInt()) or (rr shl 16) or (rg shl 8) or rb
    }

    private fun wetTintFor(wet: Float): Int {
        if (wet <= 0.02f) return 0xFFFFFFFF.toInt()
        val k = (wet * 0.32f).coerceAtMost(0.45f)
        val v = (255 * (1f - k)).toInt().coerceIn(0, 255)
        return 0xFF000000.toInt() or (v shl 16) or ((v + 6).coerceIn(0, 255) shl 8) or (v + 14).coerceIn(0, 255)
    }

    private fun limb(r: Renderer3D, m: Material, ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, radius: Float) {
        r.drawLine(ax, ay, az, bx, by, bz, radius, m)
    }

    private fun limbTinted(r: Renderer3D, m: Material, ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, radius: Float, tint: Int) {
        r.drawLine(ax, ay, az, bx, by, bz, radius, m, tint)
    }

    private fun sph(r: Renderer3D, m: Material, x: Float, y: Float, z: Float, radius: Float) {
        r.draw(Mesh.unitSphere, m, x, y, z, 0f, radius * 2f)
    }

    private fun sphTinted(r: Renderer3D, m: Material, x: Float, y: Float, z: Float, radius: Float, tint: Int) {
        if (tint == 0xFFFFFFFF.toInt()) sph(r, m, x, y, z, radius)
        else r.draw(Mesh.unitSphere, m, x, y, z, 0f, radius * 2f, tint)
    }

    private fun sphx(r: Renderer3D, m: Material, x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, yaw: Float = 0f) {
        r.draw(Mesh.unitSphere, m, x, y, z, yaw, 0f, 0f, sx * 2f, sy * 2f, sz * 2f)
    }

    private fun sphxTinted(r: Renderer3D, m: Material, x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, yaw: Float, tint: Int) {
        if (tint == 0xFFFFFFFF.toInt()) sphx(r, m, x, y, z, sx, sy, sz, yaw)
        else r.draw(Mesh.unitSphere, m, x, y, z, yaw, 0f, 0f, sx * 2f, sy * 2f, sz * 2f, tint)
    }
}

/**
 * Per-character world interaction state: the environment's physics on the body.
 * The game/VFX system writes these; the rig reads them every draw.
 */
class CharFx {
    /** 0..1 — rain/water immersion. Clothes darken, gloss up, drip. */
    var wetness = 0f
    /** 0..1 — dust/grime settled on clothes and armour. */
    var dust = 0f
    /** Local wind vector (m/s-ish), bends hair, coats, scarves. */
    var windX = 0f
    var windZ = 0f
    /** Extra shiver from cold (0..1), tightens the pose slightly. */
    var cold = 0f

    /** Integrates rain, dust and wind; call once per frame per character. */
    fun update(dt: Float, raining: Float = 0f, dusty: Float = 0f, windX: Float = 0f, windZ: Float = 0f) {
        wetness = (wetness + raining * dt * 0.16f).coerceAtMost(1f)
        if (raining <= 0.01f) wetness = (wetness - dt * 0.025f).coerceAtLeast(0f)
        dust = (dust + dusty * dt * 0.05f).coerceAtMost(1f)
        if (dusty <= 0.01f) dust = (dust - dt * 0.012f).coerceAtLeast(0f)
        this.windX = windX
        this.windZ = windZ
    }
}
