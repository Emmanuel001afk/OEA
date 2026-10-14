package com.oea.games

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.EditText
import android.widget.Toast
import com.oea.engine.OeaEngine
import com.oea.engine.core.GameHost
import com.oea.engine.core.GameLaunch
import com.oea.engine.core.GameResult
import com.oea.engine.core.HostUi
import com.oea.engine.core.LocalPlayer
import com.oea.engine.core.MenuItem
import com.oea.engine.core.PlayMode

/**
 * Small host for the separately installable first-party game apps. Each app
 * subclasses this once with a fixed game id, so it launches directly into a
 * full engine game rather than opening the launcher hub.
 */
open class OeaStandaloneGameActivity : Activity(), HostUi {
    protected open val standaloneGameId: String = ""
    private var host: GameHost? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            OeaGames.registerBuiltIns(applicationContext)
            val id = intent.getStringExtra(EXTRA_GAME_ID)?.takeIf { it.isNotBlank() } ?: standaloneGameId
            val descriptor = OeaEngine.registry.find(id)
            if (descriptor == null || !descriptor.supports(PlayMode.SOLO)) {
                Toast.makeText(this, "This game isn't available in this build.", Toast.LENGTH_LONG).show()
                finish()
                return
            }
            val options = descriptor.options.associate { it.id to it.default }.toMutableMap().apply {
                intent.getBundleExtra(EXTRA_GAME_OPTIONS)?.let { bundle ->
                    bundle.keySet().forEach { key -> bundle.getString(key)?.let { this[key] = it } }
                }
            }
            // Standalone entries are solo applications. Multiplayer sessions
            // are coordinated by the OEA Games hub, not passed across APKs.
            val mode = PlayMode.SOLO
            // Ask the driver first: a missing ES 3 configuration would otherwise
            // surface inside GLSurfaceView's own thread and take the whole app with
            // it, and a declared ES 2.0 minimum alone is not proof of anything.
            OeaEngine.surfaceProblem()?.let { reason ->
                Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
                finish()
                return
            }
            val launch = GameLaunch(
                mode = mode,
                options = options,
                seed = intent.getLongExtra(EXTRA_GAME_SEED, System.nanoTime()),
                localPlayers = listOf(LocalPlayer(OeaEngine.settings.playerName, OeaEngine.settings.playerColor)),
            )
            val gameHost = GameHost(this, descriptor, launch, this)
            host = gameHost
            gameHost.onFatalError = { error ->
                Toast.makeText(this, "${descriptor.title} stopped because of an error (${error.javaClass.simpleName}).", Toast.LENGTH_LONG).show()
                finish()
            }
            setContentView(gameHost.root)
            if (Build.VERSION.SDK_INT >= 33) {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                ) { handleBack() }
            }
        } catch (error: Exception) {
            Toast.makeText(this, "${error.message ?: "The game could not start."}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        host?.onResume()
    }

    override fun onPause() {
        host?.onPause()
        super.onPause()
    }

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
    override fun onBackPressed() = handleBack()

    private fun handleBack() {
        val currentHost = host ?: run { finish(); return }
        currentHost.onBackPressed {
            confirm("Leave the game?", "Your progress in this round will be lost.", "Leave", "Stay") { leave ->
                if (leave) finish()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        if (host?.dispatchKeyEvent(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
        host?.dispatchGenericMotionEvent(event) == true || super.onGenericMotionEvent(event)

    override fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun share(text: String, title: String) {
        runCatching {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), title))
        }.onFailure { toast("Nothing can share text on this phone.") }
    }

    override fun menu(title: String, subtitle: String?, items: List<MenuItem>, onPick: (String?) -> Unit) {
        val labels = items.map { it.label }.toTypedArray()
        val builder = AlertDialog.Builder(this).setTitle(title).setItems(labels) { _, which -> onPick(items[which].id) }
        if (!subtitle.isNullOrBlank()) builder.setMessage(subtitle)
        builder.setOnCancelListener { onPick(null) }
        builder.show()
    }

    override fun confirm(title: String, message: String, positive: String, negative: String, onResult: (Boolean) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> onResult(true) }
            .setNegativeButton(negative) { _, _ -> onResult(false) }
            .setOnCancelListener { onResult(false) }
            .show()
    }

    override fun prompt(title: String, hint: String, initial: String, onResult: (String?) -> Unit) {
        val input = EditText(this).apply {
            this.hint = hint
            setText(initial)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("OK") { _, _ -> onResult(input.text?.toString()) }
            .setNegativeButton("Cancel") { _, _ -> onResult(null) }
            .setOnCancelListener { onResult(null) }
            .show()
    }

    override fun exit(result: GameResult?) = finish()

    companion object {
        const val EXTRA_GAME_ID = "com.oea.games.extra.GAME_ID"
        const val EXTRA_GAME_MODE = "com.oea.games.extra.GAME_MODE"
        const val EXTRA_GAME_OPTIONS = "com.oea.games.extra.GAME_OPTIONS"
        const val EXTRA_GAME_SEED = "com.oea.games.extra.GAME_SEED"
    }
}
