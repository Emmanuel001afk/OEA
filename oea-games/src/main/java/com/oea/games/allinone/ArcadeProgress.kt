package com.oea.games.allinone

import android.content.Context
import android.content.SharedPreferences

/**
 * The arcade's memory: which level of each cabinet you have reached, the stars
 * you earned on every level, your best score, and the last cabinets you played
 * (the "Continue" card).
 *
 * Deliberately per device and never sent anywhere. Level unlocks are a courtesy,
 * not a lock: every cabinet can be replayed from level one at any difficulty.
 */
class ArcadeProgress(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("oea_allinone", Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ levels

    /** Highest level unlocked for [id] (at least 1). */
    fun unlocked(id: String): Int = prefs.getInt("lv_$id", 1).coerceIn(1, levelCap(id))

    fun isUnlocked(id: String, level: Int): Boolean = level in 1..unlocked(id)

    /**
     * Records a finished level; returns true when this cleared a level for the
     * first time.
     *
     * A loss is a real result: [stars] = 0 stores the best score and the play
     * count, but it never marks the level cleared and never unlocks the next
     * one. Only a win with stars moves the ladder.
     */
    fun record(id: String, level: Int, stars: Int, score: Long): Boolean {
        val capped = stars.coerceIn(0, 3)
        val cleared = capped > 0
        val key = "st_${id}_$level"
        val previous = prefs.getInt(key, 0)
        val bestKey = "best_${id}_$level"
        val previousScore = prefs.getLong(bestKey, Long.MIN_VALUE)
        var firstClear = false
        val editor = prefs.edit()
        if (cleared && capped > previous) editor.putInt(key, capped)
        if (score > previousScore) editor.putLong(bestKey, score)
        val cap = levelCap(id)
        val current = unlocked(id)
        if (cleared && level >= current && current < cap) {
            editor.putInt("lv_$id", (level + 1).coerceAtMost(cap))
            firstClear = true
        }
        val before = prefs.getInt("coins", 0)
        val earned = if (cleared && previous == 0) capped * 10 else 0
        if (earned > 0) editor.putInt("coins", before + earned)
        editor.putInt("played_$id", prefs.getInt("played_$id", 0) + 1)
        val recent = (listOf(id) + recent().filter { it != id }).take(6)
        editor.putString("recent", recent.joinToString(","))
        editor.apply()
        return firstClear
    }

    private fun levelCap(id: String): Int = ArcadeCatalog.byId(id)?.levels ?: 1

    fun stars(id: String, level: Int): Int = prefs.getInt("st_${id}_$level", 0).coerceIn(0, 3)

    fun best(id: String, level: Int): Long = prefs.getLong("best_${id}_$level", 0L)

    fun playedCount(id: String): Int = prefs.getInt("played_$id", 0)

    fun totalStars(): Int = ArcadeCatalog.all().sumOf { spec ->
        (1..spec.levels).sumOf { level -> stars(spec.id, level) }
    }

    fun maxStars(): Int = ArcadeCatalog.all().sumOf { it.levels } * 3

    /** 0–1 progress for a cabinet, for the little bar under its tile. */
    fun levelFraction(id: String): Float {
        val spec = ArcadeCatalog.byId(id) ?: return 0f
        val cleared = (1..spec.levels).count { stars(id, it) > 0 }
        return cleared.toFloat() / spec.levels.coerceAtLeast(1)
    }

    fun coins(): Int = prefs.getInt("coins", 0)

    fun recent(): List<String> =
        prefs.getString("recent", "")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    fun continueSpec(): ArcadeSpec? = ArcadeCatalog.mostRecent(recent())

    // -------------------------------------------------------------- settings

    fun difficulty(): ArcadeDifficulty {
        val stored = prefs.getString("difficulty", null) ?: return ArcadeDifficulty.NORMAL
        return ArcadeDifficulty.entries.firstOrNull { it.name == stored } ?: ArcadeDifficulty.NORMAL
    }

    fun setDifficulty(difficulty: ArcadeDifficulty) {
        prefs.edit().putString("difficulty", difficulty.name).apply()
    }

    fun reducedMotion(): Boolean = prefs.getBoolean("reduced_motion", false)

    fun setReducedMotion(value: Boolean) {
        prefs.edit().putBoolean("reduced_motion", value).apply()
    }

    /** Wipes only the arcade's own data; the rest of OEA is untouched. */
    fun reset() {
        prefs.edit().clear().apply()
    }
}
