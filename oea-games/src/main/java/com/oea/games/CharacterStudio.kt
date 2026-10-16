package com.oea.games

import com.oea.engine.core.GameStorage
import com.oea.engine.graphics.Align
import com.oea.engine.graphics.CharGender
import com.oea.engine.graphics.CharParts
import com.oea.engine.graphics.CharSpec
import com.oea.engine.graphics.CharRig
import com.oea.engine.graphics.Material
import com.oea.engine.ui.ButtonStyle
import com.oea.engine.ui.Hud
import com.oea.engine.math.Colors
import com.oea.engine.math.Rng

/**
 * The Character Studio: OEA's in-game character creator.
 *
 * A full-screen overlay drawn on the live 3D scene, so every tap shows the
 * change on the actual character instantly — the rig reads the same [CharSpec]
 * this UI edits. Tabs cover build, facial structure, hair, skin and layered
 * outfits; anything can be randomized and everything persists per device.
 *
 * Games own an instance, draw [drawOverlay] when [open], and hand [spec] to
 * their character's [CharRig] — see Arena's locker.
 */
class CharacterStudio(private val storage: GameStorage) {

    /** The live character definition. Editing it re-draws the character next frame. */
    val spec: CharSpec = CharSpec.fromJson(storage.getString(KEY_SPEC)) ?: CharSpec()

    var open = false
    private var tab = 0
    private var facePage = 0
    private var outfitPage = 0
    private val rng = Rng(0xC4A7)

    /** Builds the rig for this character (call once; it tracks spec.revision). */
    fun rig(): CharRig = CharRig(
        body = Material(color = 0xFF3E5A78.toInt()), pants = Material(color = 0xFF2C3440.toInt()),
        skin = Material(color = 0xFFE0B48C.toInt()), shoes = Material(color = 0xFF1B1E24.toInt()),
        spec = spec,
    )

    fun save() {
        runCatching { storage.putString(KEY_SPEC, spec.toJson().toString()) }
    }

    fun randomize() {
        spec.gender = CharGender.entries[rng.nextInt(CharGender.entries.size)]
        CharParts.randomize(spec, rng)
        save()
    }

    // ------------------------------------------------------------ rows

    private class Slider(val label: String, val get: () -> Float, val set: (Float) -> Unit)
    private class Cycle(val label: String, val options: List<String>, val get: () -> Int, val set: (Int) -> Unit)

    private val bodySliders = listOf(
        Slider("Height", { spec.body.height }, { spec.body.height = it }),
        Slider("Shoulders", { spec.body.shoulderWidth }, { spec.body.shoulderWidth = it }),
        Slider("Hips", { spec.body.hipWidth }, { spec.body.hipWidth = it }),
        Slider("Chest", { spec.body.chestDepth }, { spec.body.chestDepth = it }),
        Slider("Waist", { spec.body.waist }, { spec.body.waist = it }),
        Slider("Limbs", { spec.body.limbThickness }, { spec.body.limbThickness = it }),
    )
    private val bodyCycles = listOf(
        Cycle("Build", listOf("Masculine", "Feminine", "Neutral"),
            { spec.gender.ordinal }, { spec.gender = CharGender.entries[it] }),
        Cycle("Muscle", listOf("Light", "Average", "Athletic", "Heavy"),
            { ((spec.body.muscle * 3f).toInt()).coerceIn(0, 3) }, { spec.body.muscle = it / 3f }),
    )

    private val faceSliders = listOf(
        Slider("Face width", { spec.face.faceWidth }, { spec.face.faceWidth = it }),
        Slider("Face length", { spec.face.faceLength }, { spec.face.faceLength = it }),
        Slider("Jaw width", { spec.face.jawWidth }, { spec.face.jawWidth = it }),
        Slider("Jaw depth", { spec.face.jawDepth }, { spec.face.jawDepth = it }),
        Slider("Chin", { spec.face.chinLength }, { spec.face.chinLength = it }),
        Slider("Cheekbones", { spec.face.cheekbones }, { spec.face.cheekbones = it }),
        Slider("Brow ridge", { spec.face.browRidge }, { spec.face.browRidge = it }),
        Slider("Eye size", { spec.face.eyeSize }, { spec.face.eyeSize = it }),
        Slider("Eye spacing", { spec.face.eyeSpacing }, { spec.face.eyeSpacing = it }),
        Slider("Nose width", { spec.face.noseWidth }, { spec.face.noseWidth = it }),
        Slider("Nose length", { spec.face.noseLength }, { spec.face.noseLength = it }),
        Slider("Mouth width", { spec.face.mouthWidth }, { spec.face.mouthWidth = it }),
    )

    private val hairCycles = listOf(
        Cycle("Style", CharParts.HAIR_STYLES.map { it.label }, { spec.hair.style }, { spec.hair.style = it }),
        Cycle("Colour", hairColorNames, {
            closestIndex(spec.hair.color, CharParts.HAIR_COLORS)
        }, { spec.hair.color = CharParts.HAIR_COLORS[it] }),
        Cycle("Facial hair", listOf("None", "Stubble", "Beard"), { spec.hair.facial }, { spec.hair.facial = it }),
    )

    private val skinCycles = listOf(
        Cycle("Tone", skinToneNames, { spec.skin.tone }, { spec.skin.tone = it }),
        Cycle("Eyes", eyeColorNames, {
            closestIndex(spec.skin.eyeColor, CharParts.EYE_COLORS)
        }, { spec.skin.eyeColor = CharParts.EYE_COLORS[it] }),
    )
    private val skinSliders = listOf(
        Slider("Freckles", { spec.skin.freckles }, { spec.skin.freckles = it }),
        Slider("Scar", { spec.skin.scar }, { spec.skin.scar = it }),
        Slider("Warpaint", { spec.skin.warpaint }, { spec.skin.warpaint = it }),
    )

    private val outfitCyclesA = listOf(
        Cycle("Top", CharParts.TOPS.map { it.label }, { partIndex(CharParts.TOPS, spec.outfit.top.partId) }, { spec.outfit.top.partId = CharParts.TOPS[it].id }),
        Cycle("Top colour", colorNames, { closestIndex(spec.outfit.top.color, OUTFIT_COLORS) }, { spec.outfit.top.color = OUTFIT_COLORS[it] }),
        Cycle("Bottom", CharParts.BOTTOMS.map { it.label }, { partIndex(CharParts.BOTTOMS, spec.outfit.bottom.partId) }, { spec.outfit.bottom.partId = CharParts.BOTTOMS[it].id }),
        Cycle("Bottom colour", colorNames, { closestIndex(spec.outfit.bottom.color, OUTFIT_COLORS) }, { spec.outfit.bottom.color = OUTFIT_COLORS[it] }),
    )
    private val outfitCyclesB = listOf(
        Cycle("Outer layer", CharParts.OUTERWEAR.map { it.label }, { partIndex(CharParts.OUTERWEAR, spec.outfit.outer.partId) }, { spec.outfit.outer.partId = CharParts.OUTERWEAR[it].id }),
        Cycle("Outer colour", colorNames, { closestIndex(spec.outfit.outer.color, OUTFIT_COLORS) }, { spec.outfit.outer.color = OUTFIT_COLORS[it] }),
        Cycle("Footwear", CharParts.FOOTWEAR.map { it.label }, { partIndex(CharParts.FOOTWEAR, spec.outfit.footwear.partId) }, { spec.outfit.footwear.partId = CharParts.FOOTWEAR[it].id }),
        Cycle("Headwear", CharParts.HEADWEAR.map { it.label }, { partIndex(CharParts.HEADWEAR, spec.outfit.headwear.partId) }, { spec.outfit.headwear.partId = CharParts.HEADWEAR[it].id }),
        Cycle("Back item", CharParts.BACK.map { it.label }, { partIndex(CharParts.BACK, spec.outfit.back.partId) }, { val p = CharParts.BACK[it]; spec.outfit.back.partId = p.id; spec.outfit.back.textureKey = p.textureKey }),
    )

    private fun partIndex(list: List<CharParts.Part>, id: String): Int {
        val i = list.indexOfFirst { it.id == id }
        return if (i < 0) 0 else i
    }

    private fun closestIndex(color: Int, palette: IntArray): Int {
        var best = 0; var bestD = Int.MAX_VALUE
        for (i in palette.indices) {
            val dr = ((palette[i] shr 16) and 0xFF) - ((color shr 16) and 0xFF)
            val dg = ((palette[i] shr 8) and 0xFF) - ((color shr 8) and 0xFF)
            val db = (palette[i] and 0xFF) - (color and 0xFF)
            val d = dr * dr + dg * dg + db * db
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    // ------------------------------------------------------------ overlay

    /**
     * Draws the studio over the scene and handles taps. Returns true while the
     * studio consumes the frame (the game should stay unpaused behind it).
     */
    fun drawOverlay(hud: Hud): Boolean {
        val d = hud.density
        val w = hud.width; val h = hud.height
        hud.dim(0.28f)
        // Title card.
        hud.panel(hud.safeLeft + 12f * d, hud.safeTop + 10f * d, 220f * d, 52f * d, 0xD0141922.toInt(), 16f)
        hud.text("CHARACTER STUDIO", hud.safeLeft + 26f * d, hud.safeTop + 20f * d, 13f, 0xFFFFD54F.toInt(), bold = true, shadow = false)
        hud.text("every change applies live", hud.safeLeft + 26f * d, hud.safeTop + 38f * d, 10.5f, 0xAAFFFFFF.toInt(), shadow = false)

        // Tabs.
        val tabs = arrayOf("BODY", "FACE", "HAIR", "SKIN", "OUTFIT")
        val tabW = 62f * d
        val tabY = hud.safeTop + 72f * d
        val tabX0 = w / 2f - tabs.size * tabW / 2f
        for (i in tabs.indices) {
            val style = if (tab == i) ButtonStyle.PRIMARY else ButtonStyle.GHOST
            if (hud.button("st:tab:$i", tabs[i], tabX0 + i * tabW + 3f * d, tabY, tabW - 6f * d, 34f * d, style, sizeDp = 11f)) {
                tab = i
            }
        }

        // Rows panel.
        val panelY = tabY + 44f * d
        val panelH = 250f * d
        val panelW = 320f * d
        val panelX = w / 2f - panelW / 2f
        hud.panel(panelX, panelY, panelW, panelH, 0xD0141922.toInt(), 18f)

        when (tab) {
            0 -> {
                drawCycles(hud, bodyCycles, panelX, panelY + 10f * d, panelW, 2)
                drawSliders(hud, bodySliders, panelX, panelY + 96f * d, panelW, 6)
            }
            1 -> {
                val pages = 2
                val rows = 6
                val page = facePage % pages
                drawSliders(hud, faceSliders.subList(page * rows, minOf(faceSliders.size, page * rows + rows)), panelX, panelY + 14f * d, panelW, 6)
                if (hud.button("st:face:prev", "◀ Page", panelX + 18f * d, panelY + panelH - 44f * d, 90f * d, 32f * d, ButtonStyle.GHOST, sizeDp = 11f)) facePage = (facePage + pages - 1) % pages
                hud.text("page ${page + 1}/$pages", w / 2f, panelY + panelH - 34f * d, 11f, 0x99FFFFFF.toInt(), Align.CENTER, shadow = false)
                if (hud.button("st:face:next", "Page ▶", panelX + panelW - 108f * d, panelY + panelH - 44f * d, 90f * d, 32f * d, ButtonStyle.GHOST, sizeDp = 11f)) facePage = (facePage + 1) % pages
            }
            2 -> drawCycles(hud, hairCycles, panelX, panelY + 24f * d, panelW, 3)
            3 -> {
                drawCycles(hud, skinCycles, panelX, panelY + 14f * d, panelW, 2)
                drawSliders(hud, skinSliders, panelX, panelY + 106f * d, panelW, 3)
            }
            4 -> {
                val rows = if (outfitPage == 0) outfitCyclesA else outfitCyclesB
                drawCycles(hud, rows, panelX, panelY + 14f * d, panelW, rows.size)
                if (hud.button("st:outfit:prev", "◀ Page", panelX + 18f * d, panelY + panelH - 44f * d, 90f * d, 32f * d, ButtonStyle.GHOST, sizeDp = 11f)) outfitPage = 1 - outfitPage
                hud.text(if (outfitPage == 0) "clothes 1/2" else "gear 2/2", w / 2f, panelY + panelH - 34f * d, 11f, 0x99FFFFFF.toInt(), Align.CENTER, shadow = false)
                if (hud.button("st:outfit:next", "Page ▶", panelX + panelW - 108f * d, panelY + panelH - 44f * d, 90f * d, 32f * d, ButtonStyle.GHOST, sizeDp = 11f)) outfitPage = 1 - outfitPage
            }
        }

        // Action bar.
        val barY = panelY + panelH + 14f * d
        val bw = (w - 56f * d) / 3f
        if (hud.button("st:random", "Randomize", 16f * d, barY, bw, 44f * d, ButtonStyle.SECONDARY)) randomize()
        if (hud.button("st:reset", "Reset", 16f * d + bw + 8f * d, barY, bw, 44f * d, ButtonStyle.SECONDARY)) {
            val fresh = CharSpec()
            spec.loadJson(fresh.toJson())
            save()
        }
        if (hud.button("st:done", "Done", 16f * d + (bw + 8f * d) * 2, barY, bw, 44f * d, ButtonStyle.PRIMARY)) {
            save()
            open = false
        }
        return true
    }

    private fun drawSliders(hud: Hud, rows: List<Slider>, x: Float, y: Float, w: Float, slots: Int) {
        val d = hud.density
        val rowH = 38f * d
        for (i in 0 until minOf(slots, rows.size)) {
            val row = rows[i]
            val ry = y + i * rowH
            hud.text(row.label, x + 18f * d, ry + 9f * d, 11.5f, Colors.WHITE, shadow = false)
            val v = row.get()
            val barX = x + 120f * d
            hud.bar(barX, ry + 12f * d, 110f * d, 8f * d, v, 0xFFFFD54F.toInt(), 0x44FFFFFF)
            hud.text("${(v * 100).toInt()}%", barX + 118f * d, ry + 8f * d, 11f, 0xCCFFFFFF.toInt(), shadow = false)
            if (hud.button("st:dec:${row.label}", "−", x + w - 66f * d, ry + 2f * d, 26f * d, 28f * d, ButtonStyle.GHOST, sizeDp = 14f)) {
                row.set((v - 0.08f).coerceIn(0f, 1f)); spec.touch(); save()
            }
            if (hud.button("st:inc:${row.label}", "+", x + w - 36f * d, ry + 2f * d, 26f * d, 28f * d, ButtonStyle.GHOST, sizeDp = 14f)) {
                row.set((v + 0.08f).coerceIn(0f, 1f)); spec.touch(); save()
            }
        }
    }

    private fun drawCycles(hud: Hud, rows: List<Cycle>, x: Float, y: Float, w: Float, slots: Int) {
        val d = hud.density
        val rowH = 42f * d
        for (i in 0 until minOf(slots, rows.size)) {
            val row = rows[i]
            val ry = y + i * rowH
            hud.text(row.label, x + 18f * d, ry + 12f * d, 11.5f, Colors.WHITE, shadow = false)
            val idx = row.get().coerceIn(0, row.options.size - 1)
            val name = row.options[idx]
            if (hud.button("st:cyc:${row.label}:p", "◀", x + 118f * d, ry + 3f * d, 30f * d, 30f * d, ButtonStyle.GHOST, sizeDp = 12f)) {
                row.set((idx + row.options.size - 1) % row.options.size); spec.touch(); save()
            }
            val nameW = w - 118f * d - 68f * d
            hud.panel(x + 152f * d, ry + 3f * d, nameW, 30f * d, 0x33FFFFFF.toInt(), 10f)
            hud.text(name, x + 152f * d + nameW / 2f, ry + 11f * d, 11f, 0xFFFFE8B0.toInt(), Align.CENTER, shadow = false)
            if (hud.button("st:cyc:${row.label}:n", "▶", x + w - 34f * d, ry + 3f * d, 30f * d, 30f * d, ButtonStyle.GHOST, sizeDp = 12f)) {
                row.set((idx + 1) % row.options.size); spec.touch(); save()
            }
        }
    }

    companion object {
        private const val KEY_SPEC = "character_spec"

        private val hairColorNames = listOf(
            "Black", "Dark brown", "Brown", "Chestnut", "Light brown", "Dark blonde", "Blonde", "Platinum",
            "Auburn", "Ginger", "Grey", "White", "Violet", "Blue", "Red", "Green",
        )
        private val skinToneNames = listOf(
            "Porcelain", "Ivory", "Light", "Warm beige", "Honey", "Golden", "Caramel", "Amber",
            "Chestnut", "Umber", "Deep", "Ebony",
        )
        private val eyeColorNames = listOf("Brown", "Hazel", "Dark", "Blue", "Green", "Olive", "Violet", "Grey")
        private val colorNames = listOf("Navy", "Crimson", "Olive", "Plum", "Slate", "Amber", "Teal", "Charcoal")
        private val OUTFIT_COLORS = intArrayOf(
            0xFF3E5A78.toInt(), 0xFFB8452F.toInt(), 0xFF4E5A3E.toInt(), 0xFF6E4558.toInt(),
            0xFF2E3440.toInt(), 0xFFB8874B.toInt(), 0xFF256D4A.toInt(), 0xFF22262E.toInt(),
        )
    }
}
