package com.oea.games.arena

/**
 * Single source of truth for Send Off's match rules.
 *
 * The simulation used to carry these numbers as scattered literals (score
 * limits, round clocks, time limits) and the HUD repeated them as prose. A
 * mode is now data: the sim reads its limits from here, so a rule change only
 * has to be made once.
 */
internal object ArenaRules {
    enum class Objective { LAST_ALIVE, SCORE, LADDER, ROUNDS, CAPTURE, HILL, TICKETS, SURVIVE }

    data class Mode(
        val id: String,
        val label: String,
        val objective: Objective,
        /** Win score: kills for TDM, captures for Warfare, points for KOTH. */
        val scoreLimit: Int = 0,
        /** Overall match clock in seconds; 0 means the objective decides. */
        val timeLimit: Float = 0f,
        /** Per-round clock for round modes. */
        val roundTime: Float = 0f,
        /** Seconds until a respawn (0 = the sim default). */
        val respawnDelay: Float = 0f,
        /** Attack & Defend: tickets each captured sector drains. */
        val captureDrain: Int = 0,
        /** King of the Hill: points per second while holding the hill. */
        val hillRate: Float = 0f,
        val respawn: Boolean = false,
        val teams: Boolean = false,
        val roundBased: Boolean = false,
        val survival: Boolean = false,
        val shortRule: String,
    )

    private val modes = listOf(
        Mode("br", "Battle Royale", Objective.LAST_ALIVE, shortRule = "Last one standing · zone closes"),
        Mode("br_res", "Resurgence Royale", Objective.LAST_ALIVE, timeLimit = 600f, respawn = true, respawnDelay = 20f, shortRule = "Respawn while a teammate lives"),
        Mode("tdm", "Team Deathmatch", Objective.SCORE, scoreLimit = 40, timeLimit = 600f, respawn = true, teams = true, shortRule = "First to 40 · 10 min"),
        Mode("gun", "Gun Game", Objective.LADDER, scoreLimit = GUN_LADDER.size, timeLimit = 420f, respawn = true, shortRule = "Advance one gun per kill · pan finish"),
        Mode("cs", "Clash Squad", Objective.ROUNDS, scoreLimit = 4, roundTime = 105f, teams = true, roundBased = true, shortRule = "4v4 · first to 4 rounds"),
        Mode("wolf", "Lone Wolf", Objective.ROUNDS, scoreLimit = 3, roundTime = 90f, teams = true, roundBased = true, shortRule = "1v1 · best of 5"),
        Mode("warfare", "Warfare", Objective.CAPTURE, scoreLimit = 5, timeLimit = 900f, respawn = true, captureDrain = 0, teams = true, shortRule = "Hold sectors · first to 5"),
        Mode("warfare_koth", "King of the Hill", Objective.HILL, scoreLimit = 200, timeLimit = 900f, respawn = true, teams = true, hillRate = 1.2f, shortRule = "Hold the hill to 200"),
        Mode("warfare_ad", "Attack & Defend", Objective.TICKETS, scoreLimit = 300, timeLimit = 600f, respawn = true, teams = true, captureDrain = 50, shortRule = "Drain 300 tickets in 10 min"),
        Mode("last_day", "Last Day Survival", Objective.SURVIVE, survival = true, shortRule = "Day turns to night · endless waves"),
        Mode("lights_out", "Lights Out", Objective.SURVIVE, survival = true, shortRule = "Permanent night · muzzle-flash light"),
        Mode("haunted", "Haunted", Objective.SURVIVE, survival = true, shortRule = "Green fog · endless undead"),
    )

    fun of(id: String): Mode = modes.firstOrNull { it.id == id } ?: modes[0]
    val all: List<Mode> get() = modes

    /** One-line, accurate rule text for the briefing and pause screen. */
    fun brief(id: String): String {
        val m = of(id)
        return "${m.label} — ${m.shortRule}"
    }

    /** Format seconds as m:ss for HUD clocks. */
    fun clock(seconds: Float): String {
        val s = maxOf(0, kotlin.math.ceil(seconds).toInt())
        return "${s / 60}:${String.format(java.util.Locale.US, "%02d", s % 60)}"
    }
}
