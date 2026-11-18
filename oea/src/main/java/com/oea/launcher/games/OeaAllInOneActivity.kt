package com.oea.launcher.games

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast
import com.oea.engine.OeaEngine
import com.oea.games.allinone.ArcadeCatalog
import com.oea.games.allinone.ArcadeConfig
import com.oea.games.allinone.ArcadeDifficulty
import com.oea.games.allinone.ArcadeGameActivity
import com.oea.games.allinone.ArcadeHubHost
import com.oea.games.allinone.ArcadeHubScreen
import com.oea.games.allinone.ArcadeLobby
import com.oea.games.allinone.ArcadeMatch
import com.oea.games.allinone.ArcadePlayMode
import com.oea.games.allinone.ArcadeProgress
import com.oea.games.allinone.ArcadeSpec
import com.oea.games.allinone.ArcadeUi
import com.oea.games.allinone.ArcadeColors
import com.oea.games.ui.GameInsets

/**
 * All in One — the arcade.
 *
 * One app icon, one wall of cabinets, and every game is its own game: it opens
 * its own front screen, remembers its own levels and records, and supports the
 * same modes everywhere (solo against AI levels, two players passing one phone,
 * and host/join through the arcade's single shared lobby).
 *
 * This activity is the app; [ArcadeGameActivity] is the room a cabinet runs in,
 * in the same task, so "back" always lands on the wall.
 */
class OeaAllInOneActivity : Activity(), ArcadeHubHost {

    private lateinit var progress: ArcadeProgress
    private lateinit var screen: ArcadeHubScreen
    private var lobby: ArcadeLobby? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            OeaEngine.init(applicationContext)
        } catch (_: Throwable) {
        }
        progress = ArcadeProgress(this)
        lobby = ArcadeLobby(this, progress, ::startMatch)
        screen = ArcadeHubScreen(this, this, progress)
        GameInsets.edgeToEdge(window)
        setContentView(screen.build())
    }

    override fun onResume() {
        super.onResume()
        if (::screen.isInitialized) screen.refresh()
    }

    // ---------------------------------------------------------------- hub host

    override fun openCabinet(spec: ArcadeSpec) {
        if (spec.externalApp == "chess") {
            runCatching { startActivity(Intent(this, OeaChessGameActivity::class.java)) }
                .onFailure { toast("Chess is not available in this build.") }
            return
        }
        if (spec.create == null) {
            toast("${spec.title} is still being built.")
            return
        }
        val config = ArcadeConfig(
            level = progress.unlocked(spec.id),
            difficulty = progress.difficulty(),
            players = 1,
            seed = System.nanoTime(),
            mode = ArcadePlayMode.AI,
        )
        startActivity(ArcadeGameActivity.starter(this, spec, config))
    }

    override fun openFriends(spec: ArcadeSpec?) {
        lobby?.open(spec)
    }

    override fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun startMatch(match: ArcadeMatch) {
        startActivity(ArcadeGameActivity.starter(this, match.spec, match.config, network = match.session != null))
    }

    // ----------------------------------------------------------------- profile

    override fun openProfile() {
        ArcadeUi.dialog(
            this,
            "Your arcade",
            "${progress.totalStars()} of ${progress.maxStars()} stars · ${progress.coins()} coins · " +
                "difficulty ${progress.difficulty().label}",
        ) { body, dialog ->
            body.addView(ArcadeUi.row(this, "🎚️", "Difficulty", progress.difficulty().blurb) {
                dialog.dismiss()
                chooseDifficulty()
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ArcadeUi.dp(this@OeaAllInOneActivity, 7f) })
            body.addView(ArcadeUi.row(this, "🎬", "Motion", if (progress.reducedMotion()) "Reduced" else "Full") {
                dialog.dismiss()
                chooseMotion()
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ArcadeUi.dp(this@OeaAllInOneActivity, 7f) })
            body.addView(ArcadeUi.row(this, "♻️", "Reset arcade progress", "Levels, stars, coins and recent games. Nothing else in OEA is touched.") {
                dialog.dismiss()
                confirmReset()
            }, LinearLayout.LayoutParams(-1, -2))
            body.addView(
                ArcadeUi.text(this, progressBlock(), 11.5f, ArcadeColors.INK_SOFT).apply {
                    setPadding(0, ArcadeUi.dp(this@OeaAllInOneActivity, 12f), 0, 0)
                    setLineSpacing(ArcadeUi.dp(this@OeaAllInOneActivity, 3f).toFloat(), 1f)
                },
                LinearLayout.LayoutParams(-1, -2)
            )
        }
    }

    private fun progressBlock(): String = ArcadeCatalog.all().joinToString("\n") { spec ->
        val stars = (1..spec.levels).sumOf { progress.stars(spec.id, it) }
        val max = spec.levels * 3
        "${spec.title} — $stars/$max stars · level ${progress.unlocked(spec.id)}/${spec.levels}"
    }

    private fun chooseDifficulty() {
        val values = ArcadeDifficulty.entries
        ArcadeUi.choose(
            this,
            "Difficulty",
            "Applies to every cabinet. Each game still has its own levels on top.",
            values.map { "${it.label} — ${it.blurb}" },
            values.indexOf(progress.difficulty()),
        ) { index ->
            progress.setDifficulty(values[index])
            screen.refresh()
            toast("Difficulty: ${values[index].label}")
        }
    }

    private fun chooseMotion() {
        ArcadeUi.choose(
            this,
            "Motion",
            "Reduced motion removes the busiest animation from every cabinet.",
            listOf("Full animation", "Reduced motion"),
            if (progress.reducedMotion()) 1 else 0,
        ) { index ->
            progress.setReducedMotion(index == 1)
            screen.refresh()
        }
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("Reset arcade progress?")
            .setMessage("Every level, star, coin and recent game in All in One will be cleared. Your other OEA apps and games stay exactly as they are.")
            .setNegativeButton("Keep", null)
            .setPositiveButton("Reset") { _, _ ->
                progress.reset()
                screen.refresh()
                toast("Arcade progress reset")
            }
            .show()
    }

    override fun onDestroy() {
        lobby?.closeAll()
        super.onDestroy()
    }
}
