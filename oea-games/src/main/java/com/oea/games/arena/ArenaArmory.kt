package com.oea.games.arena

/**
 * A read-only view of one weapon, for screens outside the simulation.
 *
 * `Weapon` is internal to the arena package and carries a dozen simulation
 * numbers (spread, recoil, ammo indices) that a front-end screen has no business
 * reading. This is the *presentation* shape: a name, a glyph, a family, the six
 * bars a player can compare, and a price. It is derived from `Weapon` on demand
 * so a stat change in the sim reaches the armory without a second edit.
 */
data class WeaponInfo(
    val id: String,
    val label: String,
    val glyph: String,
    val family: String,
    val role: String,
    val price: Int,
    /** Six normalised 0..1 bars, in a fixed order. */
    val damage: Float,
    val rate: Float,
    val range: Float,
    val accuracy: Float,
    val handling: Float,
    val magazine: Float,
    /** Raw numbers for the detail list a player reads after the bars. */
    val damageRaw: Float,
    val rpm: Float,
    val rangeRaw: Float,
    val magSize: Int,
    val reloadSeconds: Float,
    val pellets: Int,
    val auto: Boolean,
)

/** The armory: the gun roster as a front screen can read it. */
object ArenaArmory {
    /** Display order of the families, familiar first. */
    val families: List<String> get() = Weapon.FAMILIES

    fun familyLabel(family: String): String = Weapon.familyLabel(family)

    /** Every gun in the game, in family order then price. */
    fun all(): List<WeaponInfo> = Weapon.entries
        .filter { it != Weapon.PAN }
        .sortedWith(compareBy({ Weapon.FAMILIES.indexOf(it.family).let { i -> if (i < 0) 99 else i } }, { it.price }))
        .map { info(it) }

    /** One gun by its enum name ("RIFLE"), or null. */
    fun find(id: String): WeaponInfo? = Weapon.entries.firstOrNull { it.name == id.uppercase() }?.let { info(it) }

    /** The starter pan is melee, not a gun — the lobby shows it as a last resort. */
    fun melee(): WeaponInfo = info(Weapon.PAN)

    /**
     * The guns that can actually spawn for a given weapon pool, in the same
     * family-then-price order as [all].
     *
     * This is the *loot table's* answer, not a second opinion: the armory used to
     * filter by a hard-coded list of families while `Weapon.lootPool` decided
     * what spawns, so the two could disagree — and did, hiding the new guns from
     * the island while showing families the pool never draws from.
     */
    fun pool(loadout: String): List<WeaponInfo> {
        // Ask the loot table itself rather than re-applying the family filter:
        // `Weapon.lootPool` is the authority on what can spawn, and it filters
        // out the pan (and anything else the tables exclude). Reading its answer
        // is what guarantees the sheet lists exactly the guns you can find.
        val ids = Weapon.lootPool(loadout).map { it.name }.toSet()
        return all().filter { it.id in ids }
    }

    /** The families a pool draws from, in display order. */
    fun poolFamilies(loadout: String): List<String> {
        val allowed = Weapon.poolFamilies(loadout).toSet()
        return families.filter { it in allowed }
    }

    private fun info(w: Weapon): WeaponInfo {
        val bars = Weapon.statBars(w)
        fun bar(name: String): Float = bars.firstOrNull { it.first == name }?.second ?: 0f
        return WeaponInfo(
            id = w.name,
            label = w.label,
            glyph = w.glyph,
            family = w.family,
            role = w.role,
            price = w.price,
            damage = bar("Damage"),
            rate = bar("Rate"),
            range = bar("Range"),
            accuracy = bar("Accuracy"),
            handling = bar("Handling"),
            magazine = bar("Magazine"),
            damageRaw = w.damage * w.pellets,
            rpm = w.rpm,
            rangeRaw = w.range,
            magSize = w.mag,
            reloadSeconds = w.reload,
            pellets = w.pellets,
            auto = w.auto,
        )
    }
}
