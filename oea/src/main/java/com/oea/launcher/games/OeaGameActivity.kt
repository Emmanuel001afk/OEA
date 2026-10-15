package com.oea.launcher.games

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import com.oea.engine.OeaEngine
import com.oea.engine.core.GameHost
import com.oea.engine.core.GameLaunch
import com.oea.engine.core.GameResult
import com.oea.engine.core.HostUi
import com.oea.engine.core.MenuItem
import com.oea.engine.core.PlayMode
import com.oea.games.OeaGames
import com.oea.launcher.data.OeaDataStore
import com.oea.launcher.fault.OeaFaultShield
import com.oea.launcher.perf.OeaGameRescue
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaScreenFailure
import com.oea.launcher.workspace.OeaDynamicIslandView

/**
 * Runs one OEA game full screen through the engine's [GameHost], and is the
 * wall between a misbehaving game and OEA Home.
 *
 * A game is the most likely thing in OEA to fail: it owns a GPU context, a
 * render loop, sockets and its own save data. So this activity assumes the game
 * *will* fault and makes sure the blast radius is the game itself:
 *
 * - every lifecycle callback and host callback runs inside [OeaFaultShield.guard];
 * - the engine reports faults through [GameHost.onFatalError], which lands on
 *   the in-game failure screen (retry, lower quality, clear save data) rather
 *   than a force-close the user cannot read;
 * - two faults within a short window mark the game unstable, so the hub offers
 *   a rescue path next time, and Home is told nothing at all happened;
 * - [onLowMemory] / [onTrimMemory] shrink the renderer before Android kills the
 *   process, which is what a big game on a small phone actually needs.
 */
class OeaGameActivity : Activity(), HostUi {
    private var host: GameHost? = null
    private var session: com.oea.engine.net.session.NetSession? = null
    private lateinit var kit: OeaKit
    private var cardIsland: OeaDynamicIslandView? = null
    private var cameraIsland: OeaDynamicIslandView? = null
    private var gameId: String = ""
    private var gameTitle: String = "The game"
    private var failed = false
    private var surfaceShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ok = OeaFaultShield.guard("game:create") { startGame() }
        if (!ok) showFailure(IllegalStateException("$gameTitle could not start on this device."))
    }

    private fun startGame() {
        OeaGames.registerBuiltIns(this)
        kit = OeaKit(this, OeaGamesTheme.palette())
        val id = intent.getStringExtra(EXTRA_GAME)
        val descriptor = id?.let { OeaEngine.registry.find(it) }
        val launch = id?.let { OeaGameLauncher.take(it) }
        if (descriptor == null || launch == null) {
            // Typically after Android restarted the process: start over from the hub.
            finish()
            return
        }
        gameId = descriptor.id
        gameTitle = descriptor.title
        // Two questions, answered before a GLSurfaceView exists.
        //
        // First: does the driver actually provide an OpenGL ES 3 configuration?
        // Asking now keeps a "no config" answer from surfacing inside GLSurfaceView's
        // own thread, where it would kill the process instead of the game.
        val problem = OeaEngine.surfaceProblem()
        if (problem != null) {
            showFailure(IllegalStateException(problem))
            return
        }
        // Second: a declared ES 2.0 minimum is not proof of anything — plenty of
        // phones run ES 3 contexts while reporting 2.0 — so the game is attempted
        // either way and the engine checks the real context once it exists.
        if (!OeaEngine.device.supportsEs3) {
            toast("Starting anyway: this phone declares OpenGL ES 2.0, so OEA will try the 3.0 context.")
        }
        val h = GameHost(this, descriptor, launch, this)
        host = h
        session = launch.session
        h.onFatalError = { error -> onGameFaulted(error) }
        setContentView(h.root)
        surfaceShown = true
        if (OeaEngine.settings.islandInGames) addIslands(h.overlay)
        // Android 16+ (target SDK 36) delivers Back only through this callback.
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
        }
        OeaGameWatchdog.running(gameId)
    }

    /**
     * The game faulted. Home must not know about it: show why, offer the fixes
     * that actually work, and let the engine shut its own threads down.
     */
    private fun onGameFaulted(error: Throwable) {
        if (isFinishing || isDestroyed) return
        showFailure(error)
    }

    private fun showFailure(error: Throwable) {
        if (failed) return
        failed = true
        OeaGameWatchdog.faulted(this, gameId, error)
        // The engine's teardown is what releases the GPU; do it before the
        // screen is replaced so a later resume never sees a half-alive host.
        val h = host
        host = null
        if (h != null) OeaFaultShield.guard("game:teardown") { h.onDestroy() }
        if (!::kit.isInitialized) {
            // Nothing was on screen yet (the game never got as far as its own
            // view), so a themed toast plus a clean finish is the honest report.
            toast("$gameTitle could not start: ${error.javaClass.simpleName}")
            finish()
            return
        }
        OeaScreenFailure.show(
            activity = this,
            screen = gameTitle,
            error = error,
            palette = OeaGamesTheme.palette(),
            retryLabel = "Play again",
            onRetry = { restartGame() },
            onClose = { finish() }
)
        addRescueOptions(error)
        toast("$gameTitle stopped; Home and the rest of OEA are unaffected.")
    }

    /** Buttons under the failure screen: the things that genuinely fix a game. */
    private fun addRescueOptions(error: Throwable) {
        val rescue = OeaGameRescue.options(this, gameId, error)
        if (rescue.isEmpty()) return
        val root = (window.decorView as? FrameLayout) ?: return
        val palette = OeaGamesTheme.palette()
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(kit.dp(16), kit.dp(10), kit.dp(16), kit.dp(18))
        }
        rescue.forEach { option ->
            val button = kit.quietButton(option.label, option.icon) {
                OeaFaultShield.guard("game:rescue") {
                    option.run()
                    if (option.restart) restartGame() else finish()
                }
            }
            button.setTextColor(palette.text)
            bar.addView(button, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = kit.dp(6); rightMargin = kit.dp(6) })
        }
        runCatching { root.addView(bar, FrameLayout.LayoutParams(-1, -2)) }
    }

    private fun restartGame() {
        val id = gameId
        val pending = OeaGameLauncher.reload(id)
        if (pending == null) {
            toast("Start $gameTitle again from OEA Games.")
            finish()
            return
        }
        startActivity(Intent(this, OeaGameActivity::class.java).putExtra(EXTRA_GAME, id))
        finish()
    }

    private fun addIslands(overlay: FrameLayout) {
        OeaFaultShield.guard("game:islands") {
            val card = OeaDynamicIslandView(this, OeaDynamicIslandView.Surface.CARD, inGame = true)
            val camera = OeaDynamicIslandView(this, OeaDynamicIslandView.Surface.CAMERA, inGame = true)
            overlay.addView(card, FrameLayout.LayoutParams(-1, -1))
            overlay.addView(camera, FrameLayout.LayoutParams(-1, -1))
            camera.onReservedHeightChanged = { card.setExtraTop(camera.reservedHeight()) }
            cardIsland = card
            cameraIsland = camera
            overlay.setOnApplyWindowInsetsListener { _, insets ->
                // Bars are hidden in games: hang the islands just below the camera cutout.
                var cutout: Rect? = null
                var top = 0
                if (Build.VERSION.SDK_INT >= 28) {
                    val c = insets.displayCutout
                    cutout = c?.boundingRects?.firstOrNull { it.top <= 0 }
                    top = cutout?.bottom ?: (c?.safeInsetTop ?: 0)
                }
                if (Build.VERSION.SDK_INT >= 30 && top == 0) top = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top / 3
                card.setTopArea(top, cutout)
                camera.setTopArea(top, cutout)
                insets
            }
        }
    }

    override fun onResume() {
        super.onResume()
        OeaFaultShield.guard("game:resume") { host?.onResume() }
        OeaFaultShield.guard("game:island-resume") {
            cardIsland?.refresh()
            cameraIsland?.refresh()
        }
    }

    override fun onPause() {
        OeaFaultShield.guard("game:pause") { host?.onPause() }
        super.onPause()
    }

    override fun onDestroy() {
        if (!failed) {
            val h = host
            host = null
            OeaFaultShield.guard("game:destroy") { h?.onDestroy() }
        }
        // The host leaving the game screen ends the match: everyone returns to the lobby.
        OeaFaultShield.guard("game:session") {
            session?.takeIf { it.isHost && it.phase == com.oea.engine.net.session.SessionPhase.PLAYING }?.endMatch()
        }
        session = null
        if (isFinishing) {
            OeaGameWatchdog.stopped(gameId)
            // A game that ran and closed cleanly is not a broken game: clear the
            // mark so the hub stops offering the repair.
            if (!failed && gameId.isNotBlank()) OeaGameRescue.noteRunOk(this, gameId)
        }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        OeaFaultShield.guard("game:focus") { host?.onWindowFocusChanged(hasFocus) }
    }

    /** A game holds textures and framebuffers; under pressure the engine shrinks first. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        OeaFaultShield.guard("game:trim") { host?.onTrimMemory(level) }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        OeaFaultShield.guard("game:low-memory") { host?.onLowMemory() }
    }

    @Deprecated("Back is routed to the game first")
    override fun onBackPressed() = handleBack()

    private fun handleBack() {
        if (failed) {
            finish()
            return
        }
        val h = host ?: run { finish(); return }
        OeaFaultShield.guard("game:back") {
            h.onBackPressed {
                kit.confirm("Leave the game?", "Your progress in this round will be lost.", "Leave", destructive = true) { finish() }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        val handled = OeaFaultShield.value("game:key", false) { host?.dispatchKeyEvent(event) == true }
        return handled || super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val handled = OeaFaultShield.value("game:motion", false) { host?.dispatchGenericMotionEvent(event) == true }
        return handled || super.onGenericMotionEvent(event)
    }

    // ---------------------------------------------------------------- HostUi

    override fun toast(message: String) = runCatching { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }.let { }

    override fun share(text: String, title: String) {
        runCatching {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), title))
        }.onFailure { toast("Nothing can share text on this phone.") }
    }

    override fun menu(title: String, subtitle: String?, items: List<MenuItem>, onPick: (String?) -> Unit) {
        if (isFinishing || failed) {
            onPick(null)
            return
        }
        var picked = false
        OeaFaultShield.guard("game:menu") {
            val dialog = kit.sheet(title, subtitle) { panel, d ->
                items.forEach { item ->
                    val row = kit.button(item.label, kit.palette.cardHigh, if (item.destructive) kit.palette.danger else kit.palette.text) {
                        picked = true
                        d.dismiss()
                        onPick(item.id)
                    }
                    panel.addView(row, LinearLayout.LayoutParams(-1, kit.dp(52)).apply { bottomMargin = kit.dp(8) })
                    val description = item.description
                    if (!description.isNullOrBlank()) {
                        panel.addView(kit.text(description, 12.5f, kit.palette.textSecondary).apply { setPadding(kit.dp(8), 0, kit.dp(8), kit.dp(8)) })
                    }
                }
            }
            dialog.setOnDismissListener { if (!picked) onPick(null) }
        }.let { if (!it && !picked) onPick(null) }
    }

    override fun confirm(title: String, message: String, positive: String, negative: String, onResult: (Boolean) -> Unit) {
        if (isFinishing || failed) {
            onResult(false)
            return
        }
        var answered = false
        OeaFaultShield.guard("game:confirm") {
            val dialog = kit.sheet(title, message) { panel, d ->
                panel.addView(kit.buttonRow(
                    kit.quietButton(negative) { answered = true; d.dismiss(); onResult(false) },
                    kit.primaryButton(positive) { answered = true; d.dismiss(); onResult(true) }
))
            }
            dialog.setOnDismissListener { if (!answered) onResult(false) }
        }.let { if (!it && !answered) onResult(false) }
    }

    override fun prompt(title: String, hint: String, initial: String, onResult: (String?) -> Unit) {
        OeaFaultShield.guard("game:prompt") {
            kit.prompt(title, null, hint, initial, confirmLabel = "OK") { value -> onResult(value); null }
        }.let { if (!it) onResult(null) }
    }

    override fun exit(result: GameResult?) {
        if (!isFinishing) finish()
    }

    /** True once the game's own surface is on screen; used by the failure path. */
    internal fun hasGameSurface(): Boolean = surfaceShown && !failed

    companion object {
        const val EXTRA_GAME = "oea.game.id"
    }
}

/** Hands a [GameLaunch] (which can carry a live network session) from the hub to [OeaGameActivity]. */
object OeaGameLauncher {
    @Volatile private var pending: Pair<String, GameLaunch>? = null

    fun start(activity: Activity, gameId: String, launch: GameLaunch) {
        LastMatch.remember(gameId, LastMatch(launch.mode, launch.options, launch.localPlayers))
        pending = gameId to launch
        // Games that live inside OEA have no package the Game Boost heuristics
        // could ever classify, so the hub reports the session's start itself.
        runCatching { com.oea.launcher.gameboost.OeaGameBoost.onOeaGameOpened(activity.applicationContext, gameId) }
        activity.startActivity(Intent(activity, OeaGameActivity::class.java).putExtra(OeaGameActivity.EXTRA_GAME, gameId))
    }

    fun take(gameId: String): GameLaunch? {
        val p = pending ?: return null
        if (p.first != gameId) return null
        pending = null
        return p.second
    }

    /**
     * A "Play again" press needs the same match again. The live session of a
     * networked game cannot be restarted by the player alone, so only solo and
     * same-device matches are re-armed; everything else returns to the hub.
     */
    fun reload(gameId: String): GameLaunch? {
        val descriptor = OeaEngine.registry.find(gameId) ?: return null
        val last = LastMatch.of(gameId) ?: return null
        if (descriptor.maxPlayers > 1) return null
        val launch = GameLaunch(
            mode = last.mode,
            options = last.options,
            seed = System.nanoTime(),
            localPlayers = last.players
)
        pending = gameId to launch
        return launch
    }

    @Suppress("unused") private val modes = PlayMode.entries
}

/** What the player last started per game, so a retry does not need the whole hub again. */
internal data class LastMatch(val mode: PlayMode, val options: Map<String, String>, val players: List<com.oea.engine.core.LocalPlayer>) {
    companion object {
        private val remembered = HashMap<String, LastMatch>()

        fun remember(gameId: String, match: LastMatch) { remembered[gameId] = match }

        fun of(gameId: String): LastMatch? = remembered[gameId]
    }
}

/**
 * Watches the currently running game for the pattern that means "this game keeps
 * stopping", which the hub then turns into an offer instead of a repeat crash.
 */
internal object OeaGameWatchdog {
    private const val WINDOW_MS = 120_000L
    private const val LIMIT = 2

    @Volatile private var gameId: String = ""
    @Volatile private var startedAt = 0L
    private val faults = HashMap<String, MutableList<Long>>()

    fun running(id: String) {
        gameId = id
        startedAt = SystemClock.elapsedRealtime()
    }

    fun stopped(id: String) {
        if (gameId == id) gameId = ""
    }

    fun faulted(context: Context, id: String, error: Throwable) {
        if (id.isBlank()) return
        val now = SystemClock.elapsedRealtime()
        val list = faults.getOrPut(id) { ArrayList() }
        synchronized(list) {
            list.add(now)
            while (list.isNotEmpty() && now - list.first() > WINDOW_MS) list.removeAt(0)
            if (list.size >= LIMIT) OeaDataStore.get(context).markUnstable(id, true)
        }
        OeaFaultShield.note("game:$id", error)
    }

    /** True when the game stopped almost as soon as it opened (a save-data or GPU problem). */
    fun wasImmediateStop(): Boolean = gameId.isNotBlank() && SystemClock.elapsedRealtime() - startedAt < 6_000L

}
