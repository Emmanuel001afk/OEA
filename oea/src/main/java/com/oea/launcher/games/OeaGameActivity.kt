package com.oea.launcher.games

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.Toast
import com.oea.engine.OeaEngine
import com.oea.engine.core.GameHost
import com.oea.engine.core.GameLaunch
import com.oea.engine.core.GameResult
import com.oea.engine.core.HostUi
import com.oea.engine.core.MenuItem
import com.oea.engine.core.PlayMode
import com.oea.games.OeaGames
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.workspace.OeaDynamicIslandView

/**
 * Runs one OEA game full screen through the engine's [GameHost]. It provides
 * native menus, sharing and dialogs to the game and overlays both Dynamic
 * Island styles (card and/or camera island, following the user's setting)
 * for game events, calls and music.
 */
class OeaGameActivity : Activity(), HostUi {
    private var host: GameHost? = null
    private lateinit var kit: OeaKit
    private var cardIsland: OeaDynamicIslandView? = null
    private var cameraIsland: OeaDynamicIslandView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        if (!OeaEngine.device.supportsEs3) {
            Toast.makeText(this, "This phone's GPU doesn't support OpenGL ES 3.0, which OEA games need.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val h = GameHost(this, descriptor, launch, this)
        host = h
        h.onFatalError = { error ->
            Toast.makeText(this, "${descriptor.title} stopped because of an error (${error.javaClass.simpleName}).", Toast.LENGTH_LONG).show()
            finish()
        }
        setContentView(h.root)
        if (OeaEngine.settings.islandInGames) addIslands(h.overlay)
    }

    private fun addIslands(overlay: FrameLayout) {
        val card = OeaDynamicIslandView(this, OeaDynamicIslandView.Surface.CARD, inGame = true)
        val camera = OeaDynamicIslandView(this, OeaDynamicIslandView.Surface.CAMERA, inGame = true)
        overlay.addView(card, FrameLayout.LayoutParams(-1, -1))
        overlay.addView(camera, FrameLayout.LayoutParams(-1, -1))
        camera.onReservedHeightChanged = { card.setExtraTop(camera.reservedHeight()) }
        cardIsland = card; cameraIsland = camera
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

    override fun onResume() { super.onResume(); host?.onResume(); cardIsland?.refresh(); cameraIsland?.refresh() }
    override fun onPause() { host?.onPause(); super.onPause() }

    override fun onDestroy() {
        host?.onDestroy()
        host = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        host?.onWindowFocusChanged(hasFocus)
    }

    @Deprecated("Back is routed to the game first")
    override fun onBackPressed() {
        val h = host ?: run { finish(); return }
        h.onBackPressed {
            kit.confirm("Leave the game?", "Your progress in this round will be lost.", "Leave", destructive = true) { finish() }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        if (host?.dispatchKeyEvent(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean = host?.dispatchGenericMotionEvent(event) == true || super.onGenericMotionEvent(event)

    // ---------------------------------------------------------------- HostUi

    override fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun share(text: String, title: String) {
        runCatching {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), title))
        }.onFailure { toast("Nothing can share text on this phone.") }
    }

    override fun menu(title: String, subtitle: String?, items: List<MenuItem>, onPick: (String?) -> Unit) {
        if (isFinishing) return
        var picked = false
        val dialog = kit.sheet(title, subtitle) { panel, d ->
            for (item in items) {
                val row = kit.button(item.label, kit.palette.cardHigh, if (item.destructive) kit.palette.danger else kit.palette.text) {
                    picked = true
                    d.dismiss()
                    onPick(item.id)
                }
                panel.addView(row, android.widget.LinearLayout.LayoutParams(-1, kit.dp(52)).apply { bottomMargin = kit.dp(8) })
                val description = item.description
                if (!description.isNullOrBlank()) {
                    panel.addView(kit.text(description, 12.5f, kit.palette.textSecondary).apply { setPadding(kit.dp(8), 0, kit.dp(8), kit.dp(8)) })
                }
            }
        }
        dialog.setOnDismissListener { if (!picked) onPick(null) }
    }

    override fun confirm(title: String, message: String, positive: String, negative: String, onResult: (Boolean) -> Unit) {
        if (isFinishing) return
        var answered = false
        val d = kit.sheet(title, message) { panel, dialog ->
            panel.addView(kit.buttonRow(
                kit.quietButton(negative) { answered = true; dialog.dismiss(); onResult(false) },
                kit.primaryButton(positive) { answered = true; dialog.dismiss(); onResult(true) },
            ))
        }
        d.setOnDismissListener { if (!answered) onResult(false) }
    }

    override fun prompt(title: String, hint: String, initial: String, onResult: (String?) -> Unit) {
        kit.prompt(title, null, hint, initial, confirmLabel = "OK") { value -> onResult(value); null }
    }

    override fun exit(result: GameResult?) {
        if (!isFinishing) finish()
    }

    companion object {
        const val EXTRA_GAME = "oea.game.id"
    }
}

/** Hands a [GameLaunch] (which can carry a live network session) from the hub to [OeaGameActivity]. */
object OeaGameLauncher {
    @Volatile private var pending: Pair<String, GameLaunch>? = null

    fun start(activity: Activity, gameId: String, launch: GameLaunch) {
        pending = gameId to launch
        activity.startActivity(Intent(activity, OeaGameActivity::class.java).putExtra(OeaGameActivity.EXTRA_GAME, gameId))
    }

    fun take(gameId: String): GameLaunch? {
        val p = pending ?: return null
        if (p.first != gameId) return null
        pending = null
        return p.second
    }

    @Suppress("unused") private val modes = PlayMode.entries
}
