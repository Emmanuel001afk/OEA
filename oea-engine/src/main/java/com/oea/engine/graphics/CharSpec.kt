package com.oea.engine.graphics

import com.oea.engine.math.Rng
import org.json.JSONObject

/**
 * Modular character definition — the data layer of OEA's Character Creator.
 *
 * A [CharSpec] is pure data: gender, facial structure, body proportions, skin,
 * hair and layered clothing. It resolves to a [CharForm] (numbers and colours
 * the rig actually draws) and back to JSON for saving. Every parameter is a
 * 0..1 float around a neutral 0.5 unless noted, so a creator UI can expose one
 * slider per row without knowing anything about geometry.
 *
 * Editing a spec bumps [revision]; [CharRig] notices and rebuilds instantly —
 * that is the "apply in real time" path.
 */
class CharSpec {
    /** Bumped on every change so meshes/materials can cache and rebuild. */
    var revision = 0
        private set

    var gender = CharGender.MASCULINE
    val face = FaceSpec()
    val body = BodySpec()
    val skin = SkinSpec()
    val hair = HairSpec()
    val outfit = OutfitSpec()

    fun touch() { revision++ }

    fun copy(): CharSpec {
        val s = CharSpec()
        s.gender = gender
        s.face.copyFrom(face)
        s.body.copyFrom(body)
        s.skin.copyFrom(skin)
        s.hair.copyFrom(hair)
        s.outfit.copyFrom(outfit)
        s.revision = revision
        return s
    }

    /** Resolves to the compiled form the rig draws. Materials are created per call. */
    fun form(): CharForm = CharForm.resolve(this)

    fun toJson(): JSONObject = JSONObject().apply {
        put("v", 1)
        put("gender", gender.name)
        put("face", face.toJson())
        put("body", body.toJson())
        put("skin", skin.toJson())
        put("hair", hair.toJson())
        put("outfit", outfit.toJson())
    }

    fun loadJson(o: JSONObject) {
        gender = runCatching { CharGender.valueOf(o.optString("gender", "MASCULINE")) }.getOrDefault(CharGender.MASCULINE)
        o.optJSONObject("face")?.let { face.loadJson(it) }
        o.optJSONObject("body")?.let { body.loadJson(it) }
        o.optJSONObject("skin")?.let { skin.loadJson(it) }
        o.optJSONObject("hair")?.let { hair.loadJson(it) }
        o.optJSONObject("outfit")?.let { outfit.loadJson(it) }
        touch()
    }

    companion object {
        fun fromJson(text: String?): CharSpec? {
            if (text.isNullOrBlank()) return null
            return runCatching {
                CharSpec().apply { loadJson(JSONObject(text)) }
            }.getOrNull()
        }

        fun random(rng: Rng): CharSpec = CharSpec().also { s ->
            s.gender = CharGender.entries[rng.nextInt(CharGender.entries.size)]
            CharParts.randomize(s, rng)
            s.touch()
        }
    }
}

enum class CharGender { MASCULINE, FEMININE, NEUTRAL }

/**
 * Facial structure. All 0..1 around neutral 0.5. These drive actual geometry
 * in [CharRig] — jaw width, chin length, brow ridge, nose, eyes, cheeks.
 */
class FaceSpec {
    var faceWidth = 0.5f
    var faceLength = 0.5f
    var jawWidth = 0.5f
    var jawDepth = 0.5f
    var chinLength = 0.5f
    var chinWidth = 0.5f
    var cheekbones = 0.5f
    var foreheadSlope = 0.5f
    var browRidge = 0.5f
    var browAngle = 0.5f
    var eyeSize = 0.5f
    var eyeSpacing = 0.5f
    var eyeDepth = 0.5f
    var noseWidth = 0.5f
    var noseLength = 0.5f
    var noseBridge = 0.5f
    var mouthWidth = 0.5f
    var lipFullness = 0.5f
    var earSize = 0.5f

    fun copyFrom(o: FaceSpec) {
        faceWidth = o.faceWidth; faceLength = o.faceLength; jawWidth = o.jawWidth; jawDepth = o.jawDepth
        chinLength = o.chinLength; chinWidth = o.chinWidth; cheekbones = o.cheekbones; foreheadSlope = o.foreheadSlope
        browRidge = o.browRidge; browAngle = o.browAngle; eyeSize = o.eyeSize; eyeSpacing = o.eyeSpacing; eyeDepth = o.eyeDepth
        noseWidth = o.noseWidth; noseLength = o.noseLength; noseBridge = o.noseBridge
        mouthWidth = o.mouthWidth; lipFullness = o.lipFullness; earSize = o.earSize
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("faceWidth", faceWidth); put("faceLength", faceLength); put("jawWidth", jawWidth); put("jawDepth", jawDepth)
        put("chinLength", chinLength); put("chinWidth", chinWidth); put("cheekbones", cheekbones); put("foreheadSlope", foreheadSlope)
        put("browRidge", browRidge); put("browAngle", browAngle); put("eyeSize", eyeSize); put("eyeSpacing", eyeSpacing); put("eyeDepth", eyeDepth)
        put("noseWidth", noseWidth); put("noseLength", noseLength); put("noseBridge", noseBridge)
        put("mouthWidth", mouthWidth); put("lipFullness", lipFullness); put("earSize", earSize)
    }

    fun loadJson(o: JSONObject) {
        fun f(k: String, def: Float): Float = Math.max(0f, Math.min(1f, o.optDouble(k, def.toDouble()).toFloat()))
        faceWidth = f("faceWidth", 0.5f); faceLength = f("faceLength", 0.5f); jawWidth = f("jawWidth", 0.5f); jawDepth = f("jawDepth", 0.5f)
        chinLength = f("chinLength", 0.5f); chinWidth = f("chinWidth", 0.5f); cheekbones = f("cheekbones", 0.5f); foreheadSlope = f("foreheadSlope", 0.5f)
        browRidge = f("browRidge", 0.5f); browAngle = f("browAngle", 0.5f); eyeSize = f("eyeSize", 0.5f); eyeSpacing = f("eyeSpacing", 0.5f); eyeDepth = f("eyeDepth", 0.5f)
        noseWidth = f("noseWidth", 0.5f); noseLength = f("noseLength", 0.5f); noseBridge = f("noseBridge", 0.5f)
        mouthWidth = f("mouthWidth", 0.5f); lipFullness = f("lipFullness", 0.5f); earSize = f("earSize", 0.5f)
    }
}

/** Body proportions: also 0..1 around neutral 0.5, mapped to real skeleton scales. */
class BodySpec {
    var height = 0.5f
    var shoulderWidth = 0.5f
    var hipWidth = 0.5f
    var chestDepth = 0.5f
    var waist = 0.5f
    var limbThickness = 0.5f
    var neckLength = 0.5f
    var muscle = 0.5f

    fun copyFrom(o: BodySpec) {
        height = o.height; shoulderWidth = o.shoulderWidth; hipWidth = o.hipWidth; chestDepth = o.chestDepth
        waist = o.waist; limbThickness = o.limbThickness; neckLength = o.neckLength; muscle = o.muscle
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("height", height); put("shoulderWidth", shoulderWidth); put("hipWidth", hipWidth); put("chestDepth", chestDepth)
        put("waist", waist); put("limbThickness", limbThickness); put("neckLength", neckLength); put("muscle", muscle)
    }

    fun loadJson(o: JSONObject) {
        fun f(k: String, def: Float): Float = Math.max(0f, Math.min(1f, o.optDouble(k, def.toDouble()).toFloat()))
        height = f("height", 0.5f); shoulderWidth = f("shoulderWidth", 0.5f); hipWidth = f("hipWidth", 0.5f); chestDepth = f("chestDepth", 0.5f)
        waist = f("waist", 0.5f); limbThickness = f("limbThickness", 0.5f); neckLength = f("neckLength", 0.5f); muscle = f("muscle", 0.5f)
    }
}

/** Skin colour plus markings (freckles, scar, war paint) that ride the face/body. */
class SkinSpec {
    /** Index into [CharParts.SKIN_TONES]. */
    var tone = 3
    /** Procedural skin texture key (for example skin_pores or skin_smooth). */
    var textureKey = "skin_pores"
    /** Eye colour as ARGB. */
    var eyeColor = 0xFF4A3626.toInt()
    var freckles = 0f
    var scar = 0f
    var warpaint = 0f
    var paintColor = 0xFFB8452F.toInt()

    fun copyFrom(o: SkinSpec) {
        tone = o.tone; textureKey = o.textureKey; eyeColor = o.eyeColor; freckles = o.freckles; scar = o.scar; warpaint = o.warpaint; paintColor = o.paintColor
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("tone", tone); put("texture", textureKey); put("eyeColor", eyeColor); put("freckles", freckles); put("scar", scar)
        put("warpaint", warpaint); put("paintColor", paintColor)
    }

    fun loadJson(o: JSONObject) {
        tone = o.optInt("tone", 3).coerceIn(0, CharParts.SKIN_TONES.size - 1)
        textureKey = o.optString("texture", "skin_pores")
        eyeColor = o.optInt("eyeColor", 0xFF4A3626.toInt())
        freckles = o.optDouble("freckles", 0.0).toFloat().coerceIn(0f, 1f)
        scar = o.optDouble("scar", 0.0).toFloat().coerceIn(0f, 1f)
        warpaint = o.optDouble("warpaint", 0.0).toFloat().coerceIn(0f, 1f)
        paintColor = o.optInt("paintColor", 0xFFB8452F.toInt())
    }
}

/** Hair + facial hair. [style] indexes [CharParts.HAIR_STYLES]. */
class HairSpec {
    var style = 0
    var color = 0xFF2E2118.toInt()
    /** 0 none, 1 stubble, 2 beard. */
    var facial = 0

    fun copyFrom(o: HairSpec) {
        style = o.style; color = o.color; facial = o.facial
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("style", style); put("color", color); put("facial", facial)
    }

    fun loadJson(o: JSONObject) {
        style = o.optInt("style", 0).coerceIn(0, CharParts.HAIR_STYLES.size - 1)
        color = o.optInt("color", 0xFF2E2118.toInt())
        facial = o.optInt("facial", 0).coerceIn(0, 2)
    }
}

/** One clothing layer: a part id from the catalogs plus colour, trim and texture. */
class OutfitLayer(var partId: String = "none", var color: Int = 0xFF3A4A5E.toInt(), var trim: Int = 0xFF24303E.toInt(), var textureKey: String = "fabric") {
    fun copyFrom(o: OutfitLayer) {
        partId = o.partId; color = o.color; trim = o.trim; textureKey = o.textureKey
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", partId); put("color", color); put("trim", trim); put("texture", textureKey)
    }

    fun loadJson(o: JSONObject) {
        partId = o.optString("id", "none")
        color = o.optInt("color", color)
        trim = o.optInt("trim", trim)
        textureKey = o.optString("texture", textureKey)
    }
}

/** Layered clothing: base outfit plus optional outerwear / headwear / back item. */
class OutfitSpec {
    val top = OutfitLayer("tee", 0xFF3E5A78.toInt(), textureKey = "cotton")
    val bottom = OutfitLayer("cargo", 0xFF2C3440.toInt(), textureKey = "canvas")
    val outer = OutfitLayer("none", 0xFF22262E.toInt(), textureKey = "fabric")
    val footwear = OutfitLayer("boots", 0xFF1B1E24.toInt(), textureKey = "leather")
    val headwear = OutfitLayer("none", 0xFF2A2E36.toInt(), textureKey = "fabric")
    val back = OutfitLayer("none", 0xFF3E4A32.toInt(), textureKey = "canvas")

    fun copyFrom(o: OutfitSpec) {
        top.copyFrom(o.top); bottom.copyFrom(o.bottom); outer.copyFrom(o.outer)
        footwear.copyFrom(o.footwear); headwear.copyFrom(o.headwear); back.copyFrom(o.back)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("top", top.toJson()); put("bottom", bottom.toJson()); put("outer", outer.toJson())
        put("footwear", footwear.toJson()); put("headwear", headwear.toJson()); put("back", back.toJson())
    }

    fun loadJson(o: JSONObject) {
        o.optJSONObject("top")?.let { top.loadJson(it) }
        o.optJSONObject("bottom")?.let { bottom.loadJson(it) }
        o.optJSONObject("outer")?.let { outer.loadJson(it) }
        o.optJSONObject("footwear")?.let { footwear.loadJson(it) }
        o.optJSONObject("headwear")?.let { headwear.loadJson(it) }
        o.optJSONObject("back")?.let { back.loadJson(it) }
    }
}

// ==================================================================== compiled form

/**
 * A resolved character: the numbers and colours the rig draws. Built from a
 * [CharSpec]; materials here are private clones so wetness/dust can tint them
 * without touching shared game materials.
 */
class CharForm private constructor(private val specRevision: Int) {
    // Skeleton scales (world units around the default rig).
    var heightScale = 1f
    var shoulderScale = 1f
    var hipScale = 1f
    var chestDepth = 1f
    var waistScale = 1f
    var limbScale = 1f
    var neckScale = 1f
    // Material/texture keys.
    var skinTexture = "skin_pores"
    var topTexture = "fabric"
    var bottomTexture = "fabric"
    var outerTexture = "fabric"
    var shoeTexture = "leather"
    var headwearTexture = "fabric"
    var backTexture = "canvas"
    // Head.
    var faceWidth = 1f
    var faceLength = 1f
    var jawWidth = 1f
    var jawDepth = 1f
    var chinLength = 1f
    var chinWidth = 1f
    var cheekbones = 1f
    var foreheadSlope = 1f
    var browRidge = 1f
    var browAngle = 0f
    var eyeSize = 1f
    var eyeSpacing = 1f
    var eyeDepth = 1f
    var noseWidth = 1f
    var noseLength = 1f
    var noseBridge = 1f
    var mouthWidth = 1f
    var lipFullness = 1f
    var earSize = 1f
    // Style.
    var skinColor = 0xFFE0B48C.toInt()
    var eyeColor = 0xFF4A3626.toInt()
    var hairColor = 0xFF2E2118.toInt()
    var hairStyle = 0
    var facialHair = 0
    var freckles = 0f
    var scar = 0f
    var warpaint = 0f
    var paintColor = 0xFFB8452F.toInt()
    var feminine = false
    // Clothing parts (catalog ids).
    var top = "tee"; var topColor = 0xFF3E5A78.toInt(); var topTrim = 0xFF24303E.toInt()
    var bottom = "cargo"; var bottomColor = 0xFF2C3440.toInt(); var bottomTrim = 0xFF24303E.toInt()
    var outer = "none"; var outerColor = 0xFF22262E.toInt(); var outerTrim = 0xFF15181D.toInt()
    var footwear = "boots"; var shoeColor = 0xFF1B1E24.toInt()
    var headwear = "none"; var headwearColor = 0xFF2A2E36.toInt()
    var back = "none"; var backColor = 0xFF3E4A32.toInt()

    val revision get() = specRevision

    companion object {
        /** Maps 0..1 slider space into real scale space around 1.0. */
        private fun s(v: Float, range: Float = 0.28f): Float = 1f + (v - 0.5f) * 2f * range

        fun resolve(spec: CharSpec): CharForm {
            val f = CharForm(spec.revision)
            val b = spec.body
            val fa = spec.face
            f.heightScale = s(b.height, 0.16f)
            f.shoulderScale = s(b.shoulderWidth, 0.22f)
            f.hipScale = s(b.hipWidth, 0.2f)
            f.chestDepth = s(b.chestDepth, 0.2f)
            f.waistScale = s(b.waist, 0.18f)
            f.limbScale = s(b.limbThickness, 0.2f)
            f.neckScale = s(b.neckLength, 0.2f)
            f.faceWidth = s(fa.faceWidth, 0.16f)
            f.faceLength = s(fa.faceLength, 0.14f)
            f.jawWidth = s(fa.jawWidth, 0.2f)
            f.jawDepth = s(fa.jawDepth, 0.18f)
            f.chinLength = s(fa.chinLength, 0.16f)
            f.chinWidth = s(fa.chinWidth, 0.16f)
            f.cheekbones = s(fa.cheekbones, 0.18f)
            f.foreheadSlope = fa.foreheadSlope
            f.browRidge = s(fa.browRidge, 0.3f)
            f.browAngle = (fa.browAngle - 0.5f) * 0.6f
            f.eyeSize = s(fa.eyeSize, 0.22f)
            f.eyeSpacing = s(fa.eyeSpacing, 0.18f)
            f.eyeDepth = s(fa.eyeDepth, 0.2f)
            f.noseWidth = s(fa.noseWidth, 0.28f)
            f.noseLength = s(fa.noseLength, 0.3f)
            f.noseBridge = s(fa.noseBridge, 0.25f)
            f.mouthWidth = s(fa.mouthWidth, 0.18f)
            f.lipFullness = s(fa.lipFullness, 0.3f)
            f.earSize = s(fa.earSize, 0.3f)

            f.skinColor = CharParts.SKIN_TONES[spec.skin.tone.coerceIn(0, CharParts.SKIN_TONES.size - 1)]
            f.skinTexture = spec.skin.textureKey
            f.eyeColor = spec.skin.eyeColor
            f.freckles = spec.skin.freckles
            f.scar = spec.skin.scar
            f.warpaint = spec.skin.warpaint
            f.paintColor = spec.skin.paintColor
            f.hairColor = spec.hair.color
            f.hairStyle = spec.hair.style.coerceIn(0, CharParts.HAIR_STYLES.size - 1)
            f.facialHair = spec.hair.facial
            f.feminine = spec.gender == CharGender.FEMININE ||
                (spec.gender == CharGender.NEUTRAL && b.hipWidth > b.shoulderWidth)

            val o = spec.outfit
            f.top = o.top.partId; f.topColor = o.top.color; f.topTrim = o.top.trim; f.topTexture = o.top.textureKey
            f.bottom = o.bottom.partId; f.bottomColor = o.bottom.color; f.bottomTrim = o.bottom.trim; f.bottomTexture = o.bottom.textureKey
            f.outer = o.outer.partId; f.outerColor = o.outer.color; f.outerTrim = o.outer.trim; f.outerTexture = o.outer.textureKey
            f.footwear = o.footwear.partId; f.shoeColor = o.footwear.color; f.shoeTexture = o.footwear.textureKey
            f.headwear = o.headwear.partId; f.headwearColor = o.headwear.color; f.headwearTexture = o.headwear.textureKey
            f.back = o.back.partId; f.backColor = o.back.color; f.backTexture = o.back.textureKey
            return f
        }
    }
}

// ==================================================================== data tables

/**
 * The part catalogs of the Character Creator: skin tones, hair, clothing
 * layers and named presets. Each clothing entry carries the geometry hints the
 * rig needs (fit, coverage) so new parts are data, not code.
 */
object CharParts {

    /** A clothing/gear part in a layer catalog. */
    data class Part(
        val id: String,
        val label: String,
        /** 0 skin-tight .. 1 bulky — how far the shell sits off the body. */
        val fit: Float = 0.5f,
        /** Sleeve/leg coverage 0..1 (0 = sleeveless/shorts, 1 = full). */
        val coverage: Float = 1f,
        /** Default colour for the part. */
        val color: Int = 0xFF3E5A78.toInt(),
        /** Texture/material key resolved by [CharTextures]. */
        val textureKey: String = "fabric",
        /** Drawn as a hood/collar bump behind the neck. */
        val collar: Boolean = false,
    )

    val SKIN_TONES = intArrayOf(
        0xFFF6D7C0.toInt(), 0xFFF0C7A8.toInt(), 0xFFE8B68F.toInt(), 0xFFE0B48C.toInt(),
        0xFFD19A72.toInt(), 0xFFBE8258.toInt(), 0xFFA66B45.toInt(), 0xFF8D5736.toInt(),
        0xFF74452B.toInt(), 0xFF5C3622.toInt(), 0xFF48291A.toInt(), 0xFF372014.toInt(),
    )

    val HAIR_COLORS = intArrayOf(
        0xFF1B1410.toInt(), 0xFF2E2118.toInt(), 0xFF4A3220.toInt(), 0xFF6B4A2E.toInt(),
        0xFF8A6136.toInt(), 0xFFB8874B.toInt(), 0xFFD9B36A.toInt(), 0xFFE8D5A0.toInt(),
        0xFF7A2E1E.toInt(), 0xFFA44B28.toInt(), 0xFF9AA3AE.toInt(), 0xFFEDEDF2.toInt(),
        0xFF5C4BB0.toInt(), 0xFF2F6FB2.toInt(), 0xFFB8452F.toInt(), 0xFF256D4A.toInt(),
    )

    val EYE_COLORS = intArrayOf(
        0xFF4A3626.toInt(), 0xFF6B4A2E.toInt(), 0xFF2E2118.toInt(), 0xFF3E6C8E.toInt(),
        0xFF3E7A5E.toInt(), 0xFF6E7A3E.toInt(), 0xFF7A5E8E.toInt(), 0xFF8E8E8E.toInt(),
    )

    /** Hair style ids consumed by [CharRig]. */
    val HAIR_STYLES = listOf(
        Part("short", "Short", color = 0xFF2E2118.toInt()),
        Part("ponytail", "Ponytail", color = 0xFF2E2118.toInt()),
        Part("bun", "Top bun", color = 0xFF2E2118.toInt()),
        Part("long", "Long", color = 0xFF2E2118.toInt()),
        Part("buzz", "Buzz", color = 0xFF2E2118.toInt()),
        Part("mohawk", "Mohawk", color = 0xFF2E2118.toInt()),
    )

    val TOPS = listOf(
        Part("tee", "T-shirt", fit = 0.45f, coverage = 0.45f, color = 0xFF3E5A78.toInt(), textureKey = "cotton"),
        Part("tank", "Tank top", fit = 0.25f, coverage = 0.12f, color = 0xFFB8452F.toInt(), textureKey = "cotton"),
        Part("shirt", "Dress shirt", fit = 0.5f, coverage = 0.85f, color = 0xFF7E93B8.toInt(), textureKey = "cotton_fine", collar = true),
        Part("turtleneck", "Turtleneck", fit = 0.42f, coverage = 1f, color = 0xFF2E3440.toInt(), textureKey = "knit", collar = true),
        Part("hoodie_t", "Hoodie (under)", fit = 0.7f, coverage = 0.9f, color = 0xFF4A5568.toInt(), textureKey = "fleece", collar = true),
        Part("tactical", "Tactical shirt", fit = 0.55f, coverage = 0.85f, color = 0xFF4E5A3E.toInt(), textureKey = "tactical"),
    )

    val BOTTOMS = listOf(
        Part("cargo", "Cargo pants", fit = 0.55f, coverage = 1f, color = 0xFF2C3440.toInt(), textureKey = "canvas"),
        Part("jeans", "Jeans", fit = 0.42f, coverage = 1f, color = 0xFF33415C.toInt(), textureKey = "cotton_fine"),
        Part("shorts", "Shorts", fit = 0.5f, coverage = 0.45f, color = 0xFF5C5344.toInt(), textureKey = "cotton"),
        Part("joggers", "Joggers", fit = 0.48f, coverage = 1f, color = 0xFF3A3F4A.toInt(), textureKey = "fleece"),
        Part("tactical_p", "Tactical pants", fit = 0.58f, coverage = 1f, color = 0xFF454E3A.toInt(), textureKey = "tactical"),
        Part("skirt", "Skirt", fit = 0.6f, coverage = 0.5f, color = 0xFF6E4558.toInt(), textureKey = "cotton"),
    )

    val OUTERWEAR = listOf(
        Part("none", "No outer layer", fit = 0f, coverage = 0f, color = 0xFF22262E.toInt()),
        Part("jacket", "Jacket", fit = 0.75f, coverage = 0.9f, color = 0xFF22262E.toInt(), collar = true),
        Part("hoodie", "Hoodie", fit = 0.85f, coverage = 0.95f, color = 0xFF3E4250.toInt(), collar = true),
        Part("vest", "Plate vest", fit = 0.62f, coverage = 0.55f, color = 0xFF2E3326.toInt()),
        Part("coat", "Long coat", fit = 0.9f, coverage = 1f, color = 0xFF2A2230.toInt(), collar = true),
    )

    val FOOTWEAR = listOf(
        Part("boots", "Boots", color = 0xFF1B1E24.toInt(), textureKey = "leather"),
        Part("sneakers", "Sneakers", color = 0xFFD8D8DC.toInt(), textureKey = "rubber"),
        Part("dress", "Dress shoes", color = 0xFF2A1F18.toInt(), textureKey = "leather"),
        Part("sandals", "Sandals", color = 0xFF8A6136.toInt(), textureKey = "leather"),
    )

    val HEADWEAR = listOf(
        Part("none", "Bare head", color = 0xFF2A2E36.toInt()),
        Part("cap", "Ball cap", color = 0xFF2E3440.toInt()),
        Part("beanie", "Beanie", color = 0xFF453A3E.toInt()),
        Part("helmet", "Helmet", fit = 0.7f, color = 0xFF3A4034.toInt(), textureKey = "metal"),
        Part("hijab", "Head scarf", fit = 0.55f, color = 0xFF5C4A6E.toInt(), textureKey = "cotton"),
    )

    val BACK = listOf(
        Part("none", "No pack", color = 0xFF3E4A32.toInt()),
        Part("daypack", "Day pack", fit = 0.7f, color = 0xFF3E4A32.toInt()),
        Part("quiver", "Quiver", fit = 0.35f, color = 0xFF6B4A2E.toInt()),
    )

    fun partsFor(layer: String): List<Part> = when (layer) {
        "top" -> TOPS
        "bottom" -> BOTTOMS
        "outer" -> OUTERWEAR
        "footwear" -> FOOTWEAR
        "headwear" -> HEADWEAR
        "back" -> BACK
        "hair" -> HAIR_STYLES
        else -> emptyList()
    }

    /** Fills every slot with random sensible values. */
    fun randomize(s: CharSpec, rng: Rng) {
        fun rnd() = rng.range(0.32f, 0.72f)
        s.face.faceWidth = rnd(); s.face.faceLength = rnd(); s.face.jawWidth = rnd(); s.face.jawDepth = rnd()
        s.face.chinLength = rnd(); s.face.chinWidth = rnd(); s.face.cheekbones = rnd(); s.face.foreheadSlope = rnd()
        s.face.browRidge = rnd(); s.face.browAngle = rnd(); s.face.eyeSize = rnd(); s.face.eyeSpacing = rnd(); s.face.eyeDepth = rnd()
        s.face.noseWidth = rnd(); s.face.noseLength = rnd(); s.face.noseBridge = rnd()
        s.face.mouthWidth = rnd(); s.face.lipFullness = rnd(); s.face.earSize = rnd()
        s.body.height = rnd(); s.body.shoulderWidth = rnd(); s.body.hipWidth = rnd(); s.body.chestDepth = rnd()
        s.body.waist = rnd(); s.body.limbThickness = rnd(); s.body.neckLength = rnd(); s.body.muscle = rnd()
        s.skin.tone = rng.nextInt(SKIN_TONES.size)
        s.skin.eyeColor = EYE_COLORS[rng.nextInt(EYE_COLORS.size)]
        s.skin.freckles = if (rng.chance(0.25f)) rng.range(0.2f, 0.8f) else 0f
        s.skin.scar = if (rng.chance(0.2f)) rng.range(0.2f, 0.8f) else 0f
        s.skin.warpaint = if (rng.chance(0.15f)) rng.range(0.3f, 1f) else 0f
        s.hair.style = rng.nextInt(HAIR_STYLES.size)
        s.hair.color = HAIR_COLORS[rng.nextInt(HAIR_COLORS.size)]
        s.hair.facial = if (s.gender == CharGender.MASCULINE) rng.nextInt(3) else 0
        fun pick(list: List<Part>): Part = list[rng.nextInt(list.size)]
        val top = pick(TOPS); s.outfit.top.partId = top.id; s.outfit.top.textureKey = top.textureKey
        s.outfit.top.color = listOf(0xFF3E5A78.toInt(), 0xFFB8452F.toInt(), 0xFF4E5A3E.toInt(), 0xFF6E4558.toInt(),
            0xFF2E3440.toInt(), 0xFFB8874B.toInt())[rng.nextInt(6)]
        val bottom = pick(BOTTOMS); s.outfit.bottom.partId = bottom.id; s.outfit.bottom.textureKey = bottom.textureKey
        val outer = pick(OUTERWEAR); s.outfit.outer.partId = outer.id; s.outfit.outer.textureKey = outer.textureKey
        val footwear = pick(FOOTWEAR); s.outfit.footwear.partId = footwear.id; s.outfit.footwear.textureKey = footwear.textureKey
        val headwear = pick(HEADWEAR); s.outfit.headwear.partId = headwear.id; s.outfit.headwear.textureKey = headwear.textureKey
        val back = pick(BACK); s.outfit.back.partId = back.id; s.outfit.back.textureKey = back.textureKey
        s.touch()
    }
}
