package com.oea.games.allinone

import android.graphics.Color
import com.oea.games.allinone.games.ArcheryGame
import com.oea.games.allinone.games.BlocksGame
import com.oea.games.allinone.games.DartsGame
import com.oea.games.allinone.games.FarmGame
import com.oea.games.allinone.games.HideSeekGame
import com.oea.games.allinone.games.LifePathGame
import com.oea.games.allinone.games.LudoGame
import com.oea.games.allinone.games.MemoryGame
import com.oea.games.allinone.games.PingPongGame
import com.oea.games.allinone.games.PoolGame
import com.oea.games.allinone.games.QuizGame
import com.oea.games.allinone.games.RacingGame
import com.oea.games.allinone.games.SlidingGame
import com.oea.games.allinone.games.SolitaireGame
import com.oea.games.allinone.games.TicTacToeGame
import com.oea.games.allinone.games.WhotGame
import com.oea.games.allinone.games.WordFinderGame
import kotlin.random.Random

/**
 * All in One — OEA's variety arcade.
 *
 * The hub in the reference picture is a wall of colourful cabinets, each one its
 * own game with its own identity, sitting next to its neighbours rather than
 * squeezed into one shared screen. This file is that wall's catalogue: one
 * [ArcadeSpec] per game holding everything the shell needs to present and start
 * it — its name, its materials, how many levels it has, how it is played, and
 * the factory that builds the actual game.
 *
 * A game itself is only [ArcadeGame]: start with a level, a difficulty and a
 * seed; tick; draw on a canvas; take touches. Nothing else. That is what makes
 * nineteen very different games fit in one app without becoming one muddy game:
 * the shell owns levels, progress, pause, results, two-player hand-off and
 * host/join, while each cabinet owns its rules.
 */

/** The shelves of the arcade. */
enum class ArcadeCategory(val label: String) {
    FAVOURITES("Favourites"),
    ARCADE("Arcade"),
    SPORTS("Sports"),
    BOARD("Board"),
    CARDS("Cards"),
    PUZZLE("Puzzle"),
    LIFE("Life & Quiz"),
}

/**
 * One difficulty ladder shared by every cabinet, so "Hard" means the same
 * promise everywhere: quicker opponents, tighter aim, less help.
 */
enum class ArcadeDifficulty(val label: String, val blurb: String, val skill: Float) {
    EASY("Easy", "Relaxed — slower opponents and a little help along the way", 0.32f),
    NORMAL("Normal", "A fair game. Loose moves get punished", 0.58f),
    HARD("Hard", "Quick opponents that punish a loose move", 0.8f),
    EXPERT("Expert", "No mercy. Every opponent aim is tight", 1f),
}

/** How a match was started. Networked play uses "same seed, compare scores". */
enum class ArcadePlayMode { AI, TWO_PLAYER, FRIENDS }

enum class ArcadeTouch { DOWN, MOVE, UP }

/** Everything a cabinet needs to build a level. */
data class ArcadeConfig(
    val level: Int = 1,
    val difficulty: ArcadeDifficulty = ArcadeDifficulty.NORMAL,
    val players: Int = 1,
    val seed: Long = 0L,
    val mode: ArcadePlayMode = ArcadePlayMode.AI,
)

/** The end of a level, as the shell shows it. */
data class ArcadeResult(
    val won: Boolean?,
    val headline: String,
    val detail: String,
    val score: Long,
    /** 1–3; 0 lets the shell work it out from [won] and the difficulty. */
    val stars: Int = 0,
)

/**
 * One playable cabinet.
 *
 * All values are in the view's pixel space: [draw] receives the live width and
 * height and every game lays itself out proportionally, so the same cabinet
 * fills a small phone and a tablet. Touch coordinates arrive in the same space.
 */
interface ArcadeGame {
    fun start(config: ArcadeConfig)
    fun update(dt: Float)

    /** Draws the playfield into `0,0 – width,height`. */
    fun draw(canvas: android.graphics.Canvas, width: Float, height: Float)

    /** True when the game consumed the touch. [width]/[height] are the board's current pixel size. */
    fun touch(phase: ArcadeTouch, x: Float, y: Float, width: Float, height: Float): Boolean

    /** One line under the board — whose turn, what to do, how many darts left. */
    fun status(): String

    /** A short nudge shown while the player is learning (empty when settled). */
    fun hint(): String = ""

    /** Current score, for records and for a networked challenge. */
    fun score(): Long = 0

    /** Non-null once the level is over; the board stays on screen behind the card. */
    fun result(): ArcadeResult? = null

    /** Pause and resume, for games with clocks or moving pieces. */
    fun setPaused(paused: Boolean) {}

    /** True when the game is still delivering real content after the result. */
    fun finished(): Boolean = result() != null
}

/** Shared plumbing: seeded randomness, elapsed time, a single result. */
abstract class ArcadeBaseGame : ArcadeGame {
    protected lateinit var config: ArcadeConfig
        private set
    protected lateinit var random: Random
        private set
    protected var elapsed = 0f
        private set
    private var outcome: ArcadeResult? = null

    final override fun start(config: ArcadeConfig) {
        this.config = config
        this.random = Random(config.seed)
        this.elapsed = 0f
        this.outcome = null
        onStart()
    }

    protected open fun onStart() {}

    final override fun setPaused(paused: Boolean) {
        if (paused) onPaused() else onResumed()
    }

    protected open fun onPaused() {}
    protected open fun onResumed() {}

    override fun update(dt: Float) {
        elapsed += dt
    }

    override fun touch(phase: ArcadeTouch, x: Float, y: Float, width: Float, height: Float): Boolean = false

    override fun status(): String = ""

    override fun hint(): String = ""

    override fun score(): Long = 0

    final override fun result(): ArcadeResult? = outcome

    protected fun finish(
        won: Boolean?,
        headline: String,
        detail: String,
        score: Long = score(),
        stars: Int = 0,
    ) {
        if (outcome == null) outcome = ArcadeResult(won, headline, detail, score, stars)
    }

    protected fun done(): Boolean = outcome != null

    /** Difficulty as 0 (easy) – 1 (expert), for games that tune numbers. */
    protected val skill: Float get() = config.difficulty.skill

    /** A random float that keeps every networked device on the same level. */
    protected fun rand(): Float = random.nextFloat()
}

/** One cabinet on the wall. */
data class ArcadeSpec(
    val id: String,
    val title: String,
    val tagline: String,
    val category: ArcadeCategory,
    val accent: Int,
    val art: ArcadeArtKind,
    /** Levels 1..[levels], each one harder or bigger than the last. */
    val levels: Int,
    val levelNames: List<String> = emptyList(),
    val badge: String? = null,
    val badgeColor: Int = 0,
    val twoPlayer: Boolean = false,
    val friends: Boolean = true,
    val howTo: List<Pair<String, String>> = emptyList(),
    /** Set for a cabinet that opens another OEA app (Chess) instead of a canvas game. */
    val externalApp: String? = null,
    val create: ((ArcadeConfig) -> ArcadeGame)? = null,
) {
    fun levelName(level: Int): String =
        levelNames.getOrNull((level - 1).coerceAtLeast(0)) ?: "Level $level"

    val isPlayable: Boolean get() = externalApp != null || create != null
}

object ArcadeColors {
    val CREAM = 0xFFF6ECDF.toInt()
    val CREAM_DEEP = 0xFFEFE1CF.toInt()
    val INK = 0xFF2B2635.toInt()
    val INK_SOFT = 0xFF6B6275.toInt()
    val WHITE = Color.WHITE

    const val PINK = 0xFFF2547D.toInt()
    const val CORAL = 0xFFFF7A59.toInt()
    const val GOLD = 0xFFFFC24B.toInt()
    const val SKY = 0xFF3EC6E0.toInt()
    const val BLUE = 0xFF4C7DF0.toInt()
    const val MINT = 0xFF43D6A2.toInt()
    const val LIME = 0xFF9EDB4C.toInt()
    const val LILAC = 0xFF9B7BEF.toInt()
    const val NIGHT = 0xFF221F2E.toInt()
    const val PLUM = 0xFF6B3FA0.toInt()
    const val DEEP_SEA = 0xFF1E5566.toInt()
    const val TANGERINE = 0xFFFF9F43.toInt()
    const val ORANGE = TANGERINE

    /** The player colours used by every two-player cabinet. */
    val PLAYER_ONE = 0xFFEF5B6E.toInt()
    val PLAYER_TWO = 0xFF3E8EE0.toInt()
}

/**
 * The wall. Order is deliberate: the three cabinets that show a badge sit near
 * the top, and the shelves after them run from quick arcade games to the long
 * life simulation at the bottom, which is how a player actually browses.
 */
object ArcadeCatalog {

    private val all: List<ArcadeSpec> = listOf(
        ArcadeSpec(
            id = "racing",
            title = "CAR RACING",
            tagline = "Three laps, four rivals, one racing line",
            category = ArcadeCategory.ARCADE,
            accent = ArcadeColors.CORAL,
            art = ArcadeArtKind.RACING,
            levels = 12,
            levelNames = listOf(
                "Sunset Circuit", "Harbour Run", "Market Sprint", "Night Ridge",
                "Coast Road", "Dust Valley", "Tunnel Dash", "City Loop",
                "Mountain Pass", "Storm Straight", "Grand Prix", "OEA Champion",
            ),
            badge = "MOST POPULAR",
            badgeColor = ArcadeColors.SKY,
            howTo = listOf(
                "🖐" to "Drag left or right anywhere on the road to steer — the car follows your thumb",
                "⛽" to "Speed builds on its own. The faster you go, the harder the corners are",
                "🚗" to "Pass the rivals cleanly. Touching one spins you and costs a place",
                "🏁" to "Finish three laps ahead of the field to unlock the next circuit",
            ),
            create = { RacingGame() },
        ),
        ArcadeSpec(
            id = "life",
            title = "LIFE PATH",
            tagline = "Choose a job, live twelve months of it",
            category = ArcadeCategory.LIFE,
            accent = ArcadeColors.MINT,
            art = ArcadeArtKind.LIFE,
            levels = 10,
            levelNames = listOf(
                "First job", "Rent week", "You got promoted?", "The interview",
                "Family calls", "A hard month", "Big break", "Manager",
                "Give back", "The good life",
            ),
            badge = "NEW!",
            badgeColor = ArcadeColors.PINK,
            howTo = listOf(
                "💼" to "Pick your work: trader, teacher, doctor, engineer, chef or footballer",
                "📅" to "Each month brings a decision. Money, health, happiness and skill all move",
                "🤝" to "Manners matter: how you speak to people changes what they offer you",
                "🎤" to "Interviews are real questions. Answer well to raise your pay and grade",
            ),
            create = { LifePathGame() },
        ),
        ArcadeSpec(
            id = "pool",
            title = "POOL",
            tagline = "Nine balls, six pockets, no excuses",
            category = ArcadeCategory.SPORTS,
            accent = ArcadeColors.MINT,
            art = ArcadeArtKind.POOL,
            levels = 10,
            twoPlayer = true,
            howTo = listOf(
                "🎯" to "Drag from the cue ball to aim, then pull back further for more power",
                "⚪" to "Sink every coloured ball before your opponent. The white comes back if it drops",
                "🪄" to "A foul gives the opponent a free shot — the table shows it",
                "👥" to "Play the OEA AI or hand the phone over: two players, one table",
            ),
            create = { PoolGame() },
        ),
        ArcadeSpec(
            id = "darts",
            title = "DARTS",
            tagline = "Five-oh-one, three darts, a shaking hand",
            category = ArcadeCategory.SPORTS,
            accent = ArcadeColors.PINK,
            art = ArcadeArtKind.DARTS,
            levels = 12,
            levelNames = listOf(
                "501 · pub night", "501 · league", "301 · quick legs", "501 · county",
                "501 · doubles out", "301 · doubles out", "501 · regional", "501 · national",
                "501 · tour card", "501 · major", "501 · world series", "501 · world final",
            ),
            twoPlayer = true,
            howTo = listOf(
                "🖐" to "Drag on the board to place the aim, then let go to throw",
                "🎯" to "The triple and double rings score three and two times the sector",
                "🐂" to "The bull is 50. The outer bull is 25",
                "🏁" to "Start on 501 and finish exactly on zero. Go below and the leg resets",
            ),
            create = { DartsGame() },
        ),
        ArcadeSpec(
            id = "archery",
            title = "ARCHERY",
            tagline = "Read the wind, then hold your breath",
            category = ArcadeCategory.SPORTS,
            accent = ArcadeColors.LIME,
            art = ArcadeArtKind.ARCHERY,
            levels = 10,
            levelNames = listOf("Still morning", "Light breeze", "Cross wind", "Gusty field", "Long range",
                "Rising wind", "Tricky gusts", "Championship", "Olympic round", "Gold final"),
            twoPlayer = true,
            howTo = listOf(
                "🏹" to "Pull down to draw the bow: further is stronger, and the sight rises",
                "🌬" to "The wind arrow is the truth. Aim off to the side to cancel it",
                "🎯" to "Gold is 10, red 8, blue 6, black 4, white 2 — closest to the middle wins",
                "👥" to "Take turns on one phone, or shoot it out against the OEA AI",
            ),
            create = { ArcheryGame() },
        ),
        ArcadeSpec(
            id = "blocks",
            title = "BLOCKS",
            tagline = "Fill a line, clear it, keep the board alive",
            category = ArcadeCategory.PUZZLE,
            accent = ArcadeColors.GOLD,
            art = ArcadeArtKind.BLOCKS,
            levels = 15,
            levelNames = listOf("8×8 warm-up", "8×8 steady", "8×8 quick", "8×8 tight", "8×8 pressure",
                "8×8 squeeze", "8×8 heavy", "8×8 expert", "8×8 relentless", "8×8 master",
                "9×9", "9×9 fast", "9×9 tight", "9×9 brutal", "9×9 endless"),
            howTo = listOf(
                "🧱" to "Drag a piece from the tray onto the grid",
                "✨" to "A full row or column clears and scores. Two at once scores double",
                "🚫" to "The level ends when no piece fits — keep space for the awkward shapes",
                "🎯" to "Hit the level target to unlock the next cabinet setting",
            ),
            create = { BlocksGame() },
        ),
        ArcadeSpec(
            id = "word",
            title = "WORD FINDER",
            tagline = "Swipe the letters, find every word",
            category = ArcadeCategory.PUZZLE,
            accent = ArcadeColors.BLUE,
            art = ArcadeArtKind.WORD,
            levels = 12,
            levelNames = listOf("First words", "Around the house", "Animals", "Food market", "Lagos streets",
                "Weather", "The body", "Travel", "School", "Workplace", "Big grid", "Word master"),
            howTo = listOf(
                "🔤" to "Swipe from the first letter to the last in a straight line",
                "↔" to "Words run across, down and — from level three — diagonally and backwards",
                "✅" to "Every word is listed above the grid; found ones turn green",
                "⏱" to "Find them all before the timer runs out for three stars",
            ),
            create = { WordFinderGame() },
        ),
        ArcadeSpec(
            id = "whot",
            title = "WHOT",
            tagline = "The Nigerian classic, with all its tricks",
            category = ArcadeCategory.CARDS,
            accent = ArcadeColors.PINK,
            art = ArcadeArtKind.WHOT,
            levels = 10,
            levelNames = listOf("First market", "Shape and number", "Hold on", "Pick two", "Pick three",
                "Suspension", "General market", "Whot wild", "Market champion", "Whot master"),
            twoPlayer = true,
            howTo = listOf(
                "🃏" to "Match the shape or the number of the card on the table",
                "1️⃣" to "1 holds on: the next player misses a turn",
                "2️⃣" to "2 picks two · 5 picks three · 8 is suspension · 14 is general market",
                "⭐" to "Whot (20) is wild: play it and call the shape you want next",
            ),
            create = { WhotGame() },
        ),
        ArcadeSpec(
            id = "ludo",
            title = "LUDO",
            tagline = "Roll, race, and send them home",
            category = ArcadeCategory.BOARD,
            accent = ArcadeColors.TANGERINE,
            art = ArcadeArtKind.LUDO,
            levels = 10,
            twoPlayer = true,
            howTo = listOf(
                "🎲" to "Roll a six to bring a token out of its base",
                "🏃" to "Tap a token to move it along the track by the dice",
                "⚔" to "Landing on an opponent sends it back to base — unless it is on a star",
                "🏠" to "Get all four tokens home first to win the level",
            ),
            create = { LudoGame() },
        ),
        ArcadeSpec(
            id = "solitaire",
            title = "SOLITAIRE",
            tagline = "Klondike, with the cards you know",
            category = ArcadeCategory.CARDS,
            accent = ArcadeColors.MINT,
            art = ArcadeArtKind.SOLITAIRE,
            levels = 8,
            levelNames = listOf("Draw one · easy", "Draw one", "Draw one · steady", "Draw three",
                "Draw three · steady", "Draw three · tight", "Draw three · timed", "Master deal"),
            howTo = listOf(
                "🃏" to "Tap the stock to deal. Draw one or draw three, depending on the level",
                "↕" to "Build the seven columns down in alternating colours",
                "🏠" to "Send aces to the foundations and build each suit up to the king",
                "⏱" to "Moves and time are counted; finishing under the level par earns three stars",
            ),
            create = { SolitaireGame() },
        ),
        ArcadeSpec(
            id = "memory",
            title = "TAP MATCH",
            tagline = "Turn them over, remember them, pair them",
            category = ArcadeCategory.ARCADE,
            accent = ArcadeColors.SKY,
            art = ArcadeArtKind.MEMORY,
            levels = 12,
            twoPlayer = true,
            howTo = listOf(
                "🃏" to "Tap two tiles. Matching pairs stay face up",
                "🧠" to "A mismatched pair turns back over — remember where they were",
                "⚡" to "Fewer moves and a faster finish earn more stars",
                "👥" to "Two players take turns; a pair scores again for the same player",
            ),
            create = { MemoryGame() },
        ),
        ArcadeSpec(
            id = "tictactoe",
            title = "TIC TAC TOE",
            tagline = "Three in a row — perfect play never loses",
            category = ArcadeCategory.BOARD,
            accent = ArcadeColors.BLUE,
            art = ArcadeArtKind.TICTACTOE,
            levels = 9,
            levelNames = listOf("First grid", "Learn the trap", "The fork", "Series of three", "Series of three",
                "Four by four", "Four by four", "Countdown", "Perfect machine"),
            twoPlayer = true,
            howTo = listOf(
                "❌" to "Take turns placing your mark. Three in a row wins",
                "🧠" to "From level three the AI sees forks. Block before you build",
                "4️⃣" to "Later levels use a four-by-four grid and need four in a row",
                "👥" to "Pass and play gives two people the same board on one phone",
            ),
            create = { TicTacToeGame() },
        ),
        ArcadeSpec(
            id = "pool2",
            title = "SNOOKER",
            tagline = "The long table, the tight pockets",
            category = ArcadeCategory.SPORTS,
            accent = ArcadeColors.DEEP_SEA,
            art = ArcadeArtKind.SNOOKER,
            levels = 8,
            levelNames = listOf("Six reds", "Six reds · tight pockets", "Ten reds", "Ten reds · cushion",
                "Fifteen reds", "Fifteen reds · tight", "Colour clearance", "The black final"),
            twoPlayer = true,
            howTo = listOf(
                "🔴" to "Red, then a colour, then a red again. Colours are respotted until the end",
                "🎯" to "Aim with a drag; power is the length of the pull",
                "⚪" to "Pot the cue ball and it is a foul: four points to your opponent",
                "🏆" to "When the reds are gone, clear the colours in order to win",
            ),
            create = { PoolGame(snooker = true) },
        ),
        ArcadeSpec(
            id = "hide",
            title = "HIDE & SEEK",
            tagline = "Dark maze. Three keys. Something is awake",
            category = ArcadeCategory.ARCADE,
            accent = ArcadeColors.NIGHT,
            art = ArcadeArtKind.HIDE,
            levels = 10,
            levelNames = listOf("One torch", "The first watcher", "Two watchers", "Bigger house", "Faster watcher",
                "Three watchers", "No map", "The cellar", "The attic", "It knows the way"),
            howTo = listOf(
                "🔦" to "You can only see inside your torch. The rest of the house is dark",
                "🗝" to "Collect every key, then reach the green exit door",
                "👹" to "The watcher follows sound and sight. Break line of sight to lose it",
                "👣" to "Tap an arrow to step. Running every step makes noise",
            ),
            create = { HideSeekGame() },
        ),
        ArcadeSpec(
            id = "puzzle",
            title = "SLIDE PUZZLE",
            tagline = "One empty square, a hundred ways to shuffle",
            category = ArcadeCategory.PUZZLE,
            accent = ArcadeColors.LILAC,
            art = ArcadeArtKind.PUZZLE,
            levels = 12,
            levelNames = listOf("3×3", "3×3 quick", "3×3 tight", "4×4", "4×4 steady", "4×4 quick",
                "4×4 tight", "5×5", "5×5 steady", "5×5 quick", "5×5 tight", "5×5 master"),
            howTo = listOf(
                "🔢" to "Tap a tile next to the empty space to slide it",
                "🧩" to "Put the numbers in order, left to right, top to bottom",
                "⏱" to "Every level has a par move count — beat it for three stars",
                "🔀" to "Every shuffle is guaranteed solvable, never a dead board",
            ),
            create = { SlidingGame() },
        ),
        ArcadeSpec(
            id = "pingpong",
            title = "PING PONG",
            tagline = "Two thumbs, one phone, first to eleven",
            category = ArcadeCategory.SPORTS,
            accent = ArcadeColors.ORANGE,
            art = ArcadeArtKind.PINGPONG,
            levels = 10,
            twoPlayer = true,
            howTo = listOf(
                "🖐" to "Drag your paddle. Solo you play the near half against the OEA AI",
                "👥" to "Two players share the screen: each thumb owns its own half",
                "🏓" to "Hit the ball past the other paddle. Serve alternates every two points",
                "🏁" to "First to eleven, two clear points. Later levels put spin on the ball",
            ),
            create = { PingPongGame() },
        ),
        ArcadeSpec(
            id = "farm",
            title = "FARM",
            tagline = "Twelve plots, four crops, one shifting market",
            category = ArcadeCategory.LIFE,
            accent = ArcadeColors.LIME,
            art = ArcadeArtKind.FARM,
            levels = 10,
            levelNames = listOf(
                "Small holding", "Wet season", "Market day", "Dear seeds", "Rain and rent",
                "The tomato year", "Price swing", "Two harvests", "The co-operative", "Farm of your own",
            ),
            howTo = listOf(
                "🌱" to "Choose a seed, tap an empty plot to plant it. Every seed costs money",
                "💧" to "Tap a growing plot to water it: watered crops grow twice as fast that night",
                "🛒" to "Tap a ripe plot to harvest and sell at that day's market price",
                "📅" to "Next Day moves the season on. Rent falls due every seventh day",
                "🎯" to "Reach the season target before day 24. Prices move — patience pays",
            ),
            create = { FarmGame() },
        ),
        ArcadeSpec(
            id = "quiz",
            title = "MANNERS & ETIQUETTE",
            tagline = "The courtesy that opens doors",
            category = ArcadeCategory.LIFE,
            accent = ArcadeColors.LILAC,
            art = ArcadeArtKind.QUIZ,
            levels = 4,
            levelNames = listOf("Everyday manners", "At the table", "On the phone", "At work"),
            howTo = listOf(
                "❓" to "Four answers, one best one — greetings, the table, the phone and the shared office",
                "🧠" to "Every answer explains the reasoning, so a wrong answer still teaches",
                "⏱" to "Ten questions a round, with a timer that rewards a quick, sure answer",
                "🤝" to "Manners are a skill: the round scores what a considerate person would actually do",
            ),
            create = { QuizGame(0) },
        ),
        ArcadeSpec(
            id = "interview",
            title = "JOB INTERVIEW",
            tagline = "Practise the room before the room is real",
            category = ArcadeCategory.LIFE,
            accent = ArcadeColors.BLUE,
            art = ArcadeArtKind.QUIZ,
            badge = "NEW!",
            badgeColor = ArcadeColors.LILAC,
            levels = 4,
            levelNames = listOf("First interview", "The hard questions", "Salary talk", "The panel"),
            howTo = listOf(
                "🎤" to "Real interview questions, answered the way a good panel wants to hear them",
                "🧾" to "CV naming, presentations, deadlines and feedback are all in the round",
                "💬" to "Each answer explains the reasoning: honesty plus method beats invention",
                "⏱" to "Ten questions a round. Seven correct is the pass mark",
            ),
            create = { QuizGame(1) },
        ),
        ArcadeSpec(
            id = "chess",
            title = "CHESS",
            tagline = "Pass and play, or face ten AI levels",
            category = ArcadeCategory.BOARD,
            accent = ArcadeColors.GOLD,
            art = ArcadeArtKind.CHESS,
            levels = 10,
            /** Opens OEA's own Chess app, keeping its clocks, levels and 3D board. */
            externalApp = "chess",
            howTo = listOf(
                "♟" to "Choose Pass & play on the Chess front screen — the board flips for each player",
                "🤖" to "Ten AI levels, from a friendly club player to a machine",
                "⏱" to "Bullet, blitz, rapid or classical clocks",
                "🌐" to "Wi-Fi or online play lives in the same Chess app",
            ),
        ),
    )

    /** Every cabinet, in shelf order. */
    fun all(): List<ArcadeSpec> = all

    fun byId(id: String): ArcadeSpec? = all.firstOrNull { it.id == id }

    fun inCategory(category: ArcadeCategory?): List<ArcadeSpec> =
        if (category == null) all else all.filter { it.category == category }

    /** The cabinet the hub's "Continue" card points at, once one has been played. */
    fun mostRecent(played: List<String>): ArcadeSpec? =
        played.asReversed().firstNotNullOfOrNull { id -> byId(id) }
}

/** Tile art is chosen per cabinet and drawn in code, never shipped as a bitmap. */
enum class ArcadeArtKind {
    RACING, DARTS, ARCHERY, BLOCKS, WORD, WHOT, SOLITAIRE, LUDO, LIFE,
    MEMORY, TICTACTOE, POOL, SNOOKER, HIDE, PINGPONG, PUZZLE, QUIZ, CHESS,
}
