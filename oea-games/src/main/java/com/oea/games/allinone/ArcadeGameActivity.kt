package com.oea.games.allinone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.view.animation.OvershootInterpolator
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import com.oea.engine.OeaEngine
import com.oea.engine.net.session.NetSession
import com.oea.engine.net.session.SessionPhase
import com.oea.games.ui.GameInsets

/**
 * The room one cabinet runs in.
 *
 * It opens on that cabinet's own front screen, then swaps to the glass for the
 * match with a minimal heads-up display: title, level, score, a pause button and
 * one line of status. When the game ends, a cream result card sits over the still
 * visible board: stars, score, what happened, and the next step (next level,
 * replay, another level, or home).
 *
 * Networked matches opened from the arcade lobby carry a shared seed, so two
 * phones play identical content. Both broadcast their score when they finish and
 * the result card names the other player — a real comparison rather than a
 * hopeful "multiplayer" badge.
 */
class ArcadeGameActivity : Activity(), ArcadeFrontHost {

    companion object {
        const val EXTRA_GAME = "arcade.game"
        const val EXTRA_LEVEL = "arcade.level"
        const val EXTRA_DIFFICULTY = "arcade.difficulty"
        const val EXTRA_MODE = "arcade.mode"
        const val EXTRA_PLAYERS = "arcade.players"
        const val EXTRA_SEED = "arcade.seed"
        const val EXTRA_NETWORK = "arcade.network"
        const val SCORE_TYPE = 40

        fun starter(context: Context, spec: ArcadeSpec, config: ArcadeConfig, network: Boolean = false): Intent =
            Intent(context, ArcadeGameActivity::class.java).apply {
                putExtra(EXTRA_GAME, spec.id)
                putExtra(EXTRA_LEVEL, config.level)
                putExtra(EXTRA_DIFFICULTY, config.difficulty.name)
                putExtra(EXTRA_MODE, config.mode.name)
                putExtra(EXTRA_PLAYERS, config.players)
                putExtra(EXTRA_SEED, config.seed)
                putExtra(EXTRA_NETWORK, network)
            }
    }

    private lateinit var progress: ArcadeProgress
    private var spec: ArcadeSpec? = null
    private var config = ArcadeConfig()
    private var session: NetSession? = null
    private var lobby: ArcadeLobby? = null

    private var root: FrameLayout? = null
    private var gameView: ArcadeGameView? = null
    private var statusText: TextView? = null
    private var scoreText: TextView? = null
    private var resultOverlay: View? = null
    private var front: ArcadeFrontScreen? = null

    private val main = Handler(Looper.getMainLooper())
    private val remoteScores = LinkedHashMap<Int, Int>()
    private var scoreSent = false
    private var resultShown = false
    private var handedOff = false
    private var pausedByDialog = false
    private var pausedByLifecycle = false

    private val ticker = object : Runnable {
        override fun run() {
            val live = gameView?.game
            if (live != null) {
                statusText?.text = live.status()
                val score = live.score()
                scoreText?.text = if (score > 0) "SCORE $score" else ""
            }
            pollScores()
            main.postDelayed(this, 220)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            OeaEngine.init(applicationContext)
        } catch (_: Throwable) {
        }
        progress = ArcadeProgress(this)
        val id = intent.getStringExtra(EXTRA_GAME).orEmpty()
        val found = ArcadeCatalog.byId(id)
        if (found == null || found.create == null) {
            Toast.makeText(this, "That cabinet isn't in this build.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        spec = found
        val difficulty = ArcadeDifficulty.entries
            .firstOrNull { it.name == intent.getStringExtra(EXTRA_DIFFICULTY) }
            ?: progress.difficulty()
        val mode = ArcadePlayMode.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_MODE) }
            ?: ArcadePlayMode.AI
        config = ArcadeConfig(
            level = intent.getIntExtra(EXTRA_LEVEL, progress.unlocked(found.id)).coerceIn(1, found.levels),
            difficulty = difficulty,
            players = if (mode == ArcadePlayMode.TWO_PLAYER) 2 else 1,
            seed = intent.getLongExtra(EXTRA_SEED, System.nanoTime()),
            mode = mode,
        )
        if (intent.getBooleanExtra(EXTRA_NETWORK, false)) {
            session = NetSession.current?.takeIf { it.phase == SessionPhase.PLAYING }
        }
        GameInsets.edgeToEdge(window)
        showFront()
    }

    // ------------------------------------------------------------------- front

    private fun showFront() {
        val cabinet = spec ?: return
        main.removeCallbacks(ticker)
        gameView?.pauseGame()
        gameView = null
        root = null
        statusText = null
        scoreText = null
        resultOverlay = null
        resultShown = false
        pausedByDialog = false
        pausedByLifecycle = false
        val screen = ArcadeFrontScreen(this, cabinet, progress, this)
        front = screen
        setContentView(screen.build())
    }

    override fun startCabinet(config: ArcadeConfig) {
        this.config = config
        scoreSent = false
        resultShown = false
        remoteScores.clear()
        showPlay()
    }

    override fun openFriends(spec: ArcadeSpec) {
        lobby?.closeAll()
        lobby = ArcadeLobby(this, progress) { match ->
            handedOff = true
            startActivity(ArcadeGameActivity.starter(this, match.spec, match.config, network = true))
            finish()
        }
        lobby?.open(spec)
    }

    override fun leaveCabinet() {
        closeSession()
        finish()
    }

    override fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // -------------------------------------------------------------------- play

    private fun showPlay() {
        val cabinet = spec ?: return
        val factory = cabinet.create ?: return
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val view = ArcadeGameView(this)
        frame.addView(view, FrameLayout.LayoutParams(-1, -1))
        val hud = buildHud(cabinet)
        frame.addView(hud, FrameLayout.LayoutParams(-1, -1))
        frame.setOnApplyWindowInsetsListener { _, insets ->
            val bars = GameInsets.bars(insets)
            hud.setPadding(0, bars[1], 0, bars[3])
            insets
        }
        root = frame
        gameView = view
        resultOverlay = null
        resultShown = false
        pausedByDialog = false
        pausedByLifecycle = false
        setContentView(frame)
        view.onFinished = { result -> runOnUiThread { showResult(result) } }
        view.attach(factory(config), config)
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    private fun buildHud(cabinet: ArcadeSpec): View {
        val overlay = FrameLayout(this)
        // Two soft scrims instead of a hard bar: the title stays legible over a
        // bright board, and the status line over a dark one, without boxing the
        // game in.
        overlay.addView(
            View(this).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0xB8000000.toInt(), 0x00000000)
                )
            },
            FrameLayout.LayoutParams(-1, ArcadeUi.dp(this, 128f), Gravity.TOP)
        )
        overlay.addView(
            View(this).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(0xA6000000.toInt(), 0x00000000)
                )
            },
            FrameLayout.LayoutParams(-1, ArcadeUi.dp(this, 110f), Gravity.BOTTOM)
        )
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ArcadeUi.dp(this@ArcadeGameActivity, 12f), ArcadeUi.dp(this@ArcadeGameActivity, 10f),
                ArcadeUi.dp(this@ArcadeGameActivity, 12f), ArcadeUi.dp(this@ArcadeGameActivity, 8f))
        }
        val pause = ArcadeUi.text(this, "❚❚", 14f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            background = ArcadeUi.rounded(Color.argb(150, 15, 15, 22), ArcadeUi.dp(this@ArcadeGameActivity, 20f))
            isClickable = true
            setOnClickListener { showPause() }
        }
        top.addView(pause, LinearLayout.LayoutParams(ArcadeUi.dp(this, 40f), ArcadeUi.dp(this, 40f)).apply { rightMargin = ArcadeUi.dp(this@ArcadeGameActivity, 10f) })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(ArcadeUi.text(this, cabinet.title, 14f, Color.WHITE, bold = true))
        titles.addView(
            ArcadeUi.text(this, "Level ${config.level} · ${cabinet.levelName(config.level)} · ${config.difficulty.label}", 10.5f, Color.argb(220, 255, 255, 255))
        )
        top.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        scoreText = ArcadeUi.text(this, "", 11.5f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(ArcadeUi.dp(this@ArcadeGameActivity, 10f), ArcadeUi.dp(this@ArcadeGameActivity, 7f),
                ArcadeUi.dp(this@ArcadeGameActivity, 10f), ArcadeUi.dp(this@ArcadeGameActivity, 7f))
            background = ArcadeUi.rounded(Color.argb(140, 15, 15, 22), ArcadeUi.dp(this@ArcadeGameActivity, 16f))
        }
        top.addView(scoreText)
        overlay.addView(
            top,
            FrameLayout.LayoutParams(-1, -2, Gravity.TOP)
        )
        statusText = ArcadeUi.text(this, cabinet.tagline, 11.5f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            setPadding(ArcadeUi.dp(this@ArcadeGameActivity, 12f), ArcadeUi.dp(this@ArcadeGameActivity, 8f),
                ArcadeUi.dp(this@ArcadeGameActivity, 12f), ArcadeUi.dp(this@ArcadeGameActivity, 8f))
            background = ArcadeUi.rounded(Color.argb(130, 12, 12, 18), ArcadeUi.dp(this@ArcadeGameActivity, 16f))
        }
        overlay.addView(
            statusText,
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = ArcadeUi.dp(this@ArcadeGameActivity, 14f)
                leftMargin = ArcadeUi.dp(this@ArcadeGameActivity, 16f)
                rightMargin = ArcadeUi.dp(this@ArcadeGameActivity, 16f)
            }
        )
        return overlay
    }

    // ------------------------------------------------------------------- pause

    private fun showPause() {
        if (resultOverlay != null) return
        pausedByDialog = true
        gameView?.pauseGame()
        val cabinet = spec ?: return
        ArcadeUi.dialog(this, "Paused", cabinet.title, 0.5f) { body, dialog ->
            body.addView(ArcadeUi.button(this, "Resume", cabinet.accent, Color.WHITE) {
                dialog.dismiss()
            }, LinearLayout.LayoutParams(-1, -2))
            body.addView(ArcadeUi.outlineButton(this, "Restart this level", ArcadeColors.INK_SOFT) {
                dialog.dismiss()
                pausedByDialog = false
                restart()
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ArcadeUi.dp(this@ArcadeGameActivity, 7f) })
            body.addView(ArcadeUi.outlineButton(this, "Difficulty: ${config.difficulty.label}", ArcadeColors.INK_SOFT) {
                val labels = ArcadeDifficulty.entries.map { "${it.label} — ${it.blurb}" }
                ArcadeUi.choose(this, "Difficulty", "Adjusting restarts the level", labels, ArcadeDifficulty.entries.indexOf(config.difficulty)) { index ->
                    config = config.copy(difficulty = ArcadeDifficulty.entries[index])
                    progress.setDifficulty(config.difficulty)
                    pausedByDialog = false
                    restart()
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ArcadeUi.dp(this@ArcadeGameActivity, 7f) })
            body.addView(ArcadeUi.outlineButton(this, "How to play", ArcadeColors.INK_SOFT) {
                showHowTo(cabinet)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ArcadeUi.dp(this@ArcadeGameActivity, 7f) })
            body.addView(ArcadeUi.outlineButton(this, "Choose a level", ArcadeColors.INK_SOFT) {
                dialog.dismiss()
                pausedByDialog = false
                gameView?.pauseGame()
                closeSession()
                showFront()
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ArcadeUi.dp(this@ArcadeGameActivity, 7f) })
            body.addView(ArcadeUi.outlineButton(this, "Leave the arcade", ArcadeColors.INK_SOFT) {
                dialog.dismiss()
                leaveCabinet()
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ArcadeUi.dp(this@ArcadeGameActivity, 7f) })
        }.setOnDismissListener {
            if (!pausedByDialog) return@setOnDismissListener
            pausedByDialog = false
            if (resultOverlay == null && !isFinishing) {
                gameView?.resumeGame()
                pausedByLifecycle = false
            }
        }
    }

    private fun showHowTo(cabinet: ArcadeSpec) {
        ArcadeUi.dialog(this, cabinet.title, "How to play", 0.55f) { body, dialog ->
            cabinet.howTo.forEach { (glyph, line) ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, ArcadeUi.dp(this@ArcadeGameActivity, 6f), 0, ArcadeUi.dp(this@ArcadeGameActivity, 6f)) }
                row.addView(ArcadeUi.text(this, glyph, 16f, ArcadeColors.INK), LinearLayout.LayoutParams(ArcadeUi.dp(this@ArcadeGameActivity, 34f), -2))
                row.addView(ArcadeUi.text(this, line, 12.5f, ArcadeColors.INK_SOFT), LinearLayout.LayoutParams(0, -2, 1f))
                body.addView(row)
            }
            body.addView(ArcadeUi.button(this, "Close", ArcadeColors.INK, Color.WHITE) { dialog.dismiss() }, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun restart() {
        val cabinet = spec ?: return
        val factory = cabinet.create ?: return
        resultOverlay?.let { root?.removeView(it) }
        resultOverlay = null
        resultShown = false
        scoreSent = false
        remoteScores.clear()
        config = config.copy(seed = System.nanoTime())
        gameView?.attach(factory(config), config)
    }

    // ------------------------------------------------------------------ result

    private fun showResult(result: ArcadeResult) {
        if (resultShown) return
        resultShown = true
        val cabinet = spec ?: return
        val stars = if (result.stars in 1..3) result.stars else when (result.won) {
            true -> 3
            false -> 1
            null -> 2
        }
        val earnedStars = if (result.won == false) 0 else stars
        val previousBest = progress.best(cabinet.id, config.level)
        val firstClear = progress.record(cabinet.id, config.level, earnedStars, result.score)
        main.removeCallbacks(ticker)
        sendScore(result.score)
        if (firstClear && config.level < cabinet.levels) {
            toast("Level ${config.level + 1} unlocked.")
        }
        buildResultCard(result, earnedStars, previousBest)
    }

    private fun buildResultCard(result: ArcadeResult, stars: Int, previousBest: Long) {
        val cabinet = spec ?: return
        val frame = root ?: return
        resultOverlay?.let { frame.removeView(it) }
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(170, 10, 8, 6))
            isClickable = true
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ArcadeUi.dp(this@ArcadeGameActivity, 20f), ArcadeUi.dp(this@ArcadeGameActivity, 18f),
                ArcadeUi.dp(this@ArcadeGameActivity, 20f), ArcadeUi.dp(this@ArcadeGameActivity, 16f))
            background = ArcadeUi.rounded(0xFFFFFBF4.toInt(), ArcadeUi.dp(this@ArcadeGameActivity, 24f))
        }
        card.addView(
            View(this).apply { background = ArcadeUi.rounded(cabinet.accent, ArcadeUi.dp(this@ArcadeGameActivity, 2f)) },
            LinearLayout.LayoutParams(ArcadeUi.dp(this@ArcadeGameActivity, 46f), ArcadeUi.dp(this@ArcadeGameActivity, 4f)).apply {
                bottomMargin = ArcadeUi.dp(this@ArcadeGameActivity, 9f)
            }
        )
        card.addView(
            ArcadeUi.text(this, if (result.won == false) "NEXT TIME" else "LEVEL CLEAR", 9.5f, cabinet.accent, bold = true)
                .apply { letterSpacing = 0.2f }
        )
        card.addView(ArcadeUi.text(this, result.headline, 23f, ArcadeColors.INK, bold = true).apply { setPadding(0, ArcadeUi.dp(this@ArcadeGameActivity, 3f), 0, ArcadeUi.dp(this@ArcadeGameActivity, 5f)) })
        val starsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val starViews = ArrayList<TextView>()
        (1..3).forEach { index ->
            val glyph = ArcadeUi.text(this, "★", 26f, if (index <= stars) ArcadeColors.GOLD else 0xFFE2D5C3.toInt(), bold = true).apply {
                setPadding(0, 0, ArcadeUi.dp(this@ArcadeGameActivity, 5f), 0)
            }
            starViews += glyph
            starsRow.addView(glyph)
        }
        card.addView(starsRow)
        starViews.forEachIndexed { index, glyph ->
            glyph.scaleX = 0f
            glyph.scaleY = 0f
            glyph.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setStartDelay((index * 90).toLong()).setDuration(260)
                .setInterpolator(OvershootInterpolator()).start()
        }
        card.addView(
            ArcadeUi.text(this, result.detail, 12.5f, ArcadeColors.INK_SOFT).apply {
                setPadding(0, ArcadeUi.dp(this@ArcadeGameActivity, 6f), 0, ArcadeUi.dp(this@ArcadeGameActivity, 6f))
                setLineSpacing(ArcadeUi.dp(this@ArcadeGameActivity, 2f).toFloat(), 1f)
            }
        )
        card.addView(
            ArcadeUi.text(
                this,
                "Score ${result.score}" + (if (result.score > previousBest && previousBest > 0) " · new best" else if (previousBest > 0) " · best $previousBest" else "") +
                    " · level ${config.level} · ${config.difficulty.label}" +
                    if (session != null) "\nShared level: both phones played the same seed" else "",
                11.5f, ArcadeColors.INK_SOFT
            )
        )
        if (session != null) {
            val names = session?.players.orEmpty().associate { it.id to it.name }
            val lines = remoteScores.entries.joinToString("\n") { (id, value) ->
                "${names[id] ?: "Friend"}: $value ${if (value > result.score) "— ahead" else if (value < result.score) "— you lead" else "— level"}"
            }
            card.addView(
                ArcadeUi.text(
                    this,
                    if (lines.isBlank()) "Waiting for your friend's score…" else "Challenge\n$lines",
                    12f, ArcadeColors.INK, bold = false
                ).apply { setPadding(0, ArcadeUi.dp(this@ArcadeGameActivity, 6f), 0, ArcadeUi.dp(this@ArcadeGameActivity, 2f)) }
            )
        }
        val nextLevel = config.level + 1
        if (result.won != false && nextLevel <= cabinet.levels) {
            card.addView(
                ArcadeUi.button(this, "Next: level $nextLevel · ${cabinet.levelName(nextLevel)}", cabinet.accent, Color.WHITE) {
                    startCabinet(config.copy(level = nextLevel, seed = System.nanoTime()))
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = ArcadeUi.dp(this@ArcadeGameActivity, 12f) }
            )
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            ArcadeUi.outlineButton(this, "Replay", ArcadeColors.INK_SOFT) {
                frame.removeView(scrim)
                resultOverlay = null
                restart()
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = ArcadeUi.dp(this@ArcadeGameActivity, 8f) }
        )
        row.addView(
            ArcadeUi.outlineButton(this, "Levels", ArcadeColors.INK_SOFT) {
                closeSession()
                frame.removeView(scrim)
                resultOverlay = null
                showFront()
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        card.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ArcadeUi.dp(this@ArcadeGameActivity, 8f) })
        card.addView(
            ArcadeUi.outlineButton(this, "Share this result", ArcadeColors.INK_SOFT) { shareResult(cabinet, result) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ArcadeUi.dp(this@ArcadeGameActivity, 7f) }
        )
        // Confetti drawn behind the card, in the cabinet's own colours.
        scrim.addView(
            BurstView(this, cabinet.accent),
            0,
            FrameLayout.LayoutParams(-1, -1)
        )
        card.addView(
            ArcadeUi.text(this, "Done", 13f, ArcadeColors.INK_SOFT, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, ArcadeUi.dp(this@ArcadeGameActivity, 12f), 0, ArcadeUi.dp(this@ArcadeGameActivity, 4f))
                isClickable = true
                setOnClickListener {
                    closeSession()
                    finish()
                }
            },
            LinearLayout.LayoutParams(-1, -2)
        )
        scrim.addView(
            card,
            FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
                leftMargin = ArcadeUi.dp(this@ArcadeGameActivity, 18f)
                rightMargin = ArcadeUi.dp(this@ArcadeGameActivity, 18f)
            }
        )
        frame.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        resultOverlay = scrim
    }

    // ----------------------------------------------------------------- network

    private fun sendScore(score: Long) {
        val s = session ?: return
        if (scoreSent) return
        scoreSent = true
        runCatching {
            s.broadcast(SCORE_TYPE) { writer -> writer.i32(score.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()) }
        }
    }

    private fun pollScores() {
        val s = session ?: return
        runCatching {
            s.poll { message ->
                if (message.type == SCORE_TYPE) {
                    val value = runCatching { message.reader().i32() }.getOrDefault(0)
                    remoteScores[message.from] = value
                }
            }
        }
    }

    private fun closeSession() {
        if (handedOff) return
        val s = session ?: return
        if (s.phase != SessionPhase.CLOSED) s.close("Left the arcade")
        session = null
    }

    // --------------------------------------------------------------- lifecycle

    override fun onBackPressed() {
        if (resultOverlay != null) {
            closeSession()
            finish()
            return
        }
        if (gameView != null && !pausedByDialog) showPause() else super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        if (gameView != null && !pausedByDialog && resultOverlay == null) {
            pausedByLifecycle = true
            gameView?.pauseGame()
        }
        main.removeCallbacks(ticker)
    }

    override fun onResume() {
        super.onResume()
        if (gameView != null && pausedByLifecycle && !pausedByDialog && resultOverlay == null) {
            pausedByLifecycle = false
            gameView?.resumeGame()
        }
        if (!resultShown && gameView != null) {
            main.removeCallbacks(ticker)
            main.post(ticker)
        }
    }

    private fun shareResult(cabinet: ArcadeSpec, result: ArcadeResult) {
        val text = "${cabinet.title} · level ${config.level} · ${result.score} points" +
            if (result.won == false) " (not cleared yet)" else " — ${result.headline}"
        runCatching {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "$text\nPlayed in All in One on OEA."),
                    "Share your result"
                )
            )
        }
    }

    /** A short, cheap confetti burst: forty pieces, one second, no assets. */
    private class BurstView(context: Context, accent: Int) : View(context) {
        private val started = System.nanoTime()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val colors = intArrayOf(accent, ArcadeColors.GOLD, ArcadeColors.MINT, ArcadeColors.SKY, ArcadeColors.WHITE)
        private val random = Random(7)
        private val pieces = Array(44) {
            floatArrayOf(
                random.nextFloat(),                      // x (0–1)
                random.nextFloat() * 0.35f,              // y start (0–1)
                random.nextFloat() * 0.5f + 0.25f,       // drift
                random.nextFloat() * 0.9f + 0.4f,        // speed
                random.nextFloat() * 360f,               // angle
                random.nextFloat() * 7f + 4f,            // size
                colors[random.nextInt(colors.size)].toFloat(),
            )
        }

        override fun onDraw(canvas: Canvas) {
            val seconds = (System.nanoTime() - started) / 1_000_000_000f
            if (seconds > 1.5f) return
            val w = width.toFloat()
            val h = height.toFloat()
            val fade = (1f - seconds / 1.5f).coerceIn(0f, 1f)
            pieces.forEach { piece ->
                val angle = Math.toRadians((piece[4] + seconds * 140f).toDouble())
                val travel = piece[3] * seconds
                val x = piece[0] * w + cos(angle).toFloat() * travel * w * 0.18f
                val y = h * (0.2f + piece[1]) + sin(angle).toFloat() * travel * h * 0.12f + seconds * seconds * h * 0.22f
                paint.color = piece[6].toInt()
                paint.alpha = (255 * fade).toInt().coerceIn(0, 255)
                canvas.drawCircle(x, y, piece[5], paint)
            }
            postInvalidateOnAnimation()
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        gameView?.pauseGame()
        lobby?.closeAll()
        if (isFinishing) closeSession()
        super.onDestroy()
    }
}
