package com.oea.standalone.runner

import com.oea.games.OeaStandaloneGameActivity

/**
 * OEA Endless Days as its own app. The gestures are the game's whole vocabulary, so
 * the front screen says them plainly rather than hiding them in a manual.
 */
class RunnerActivity : OeaStandaloneGameActivity() {
    override val standaloneGameId: String = "runner"

    override val controlLegend = listOf(
        "←" to "Swipe left to step one lane left",
        "→" to "Swipe right to step one lane right",
        "↑" to "Swipe up to jump; a short tap clears a low crate",
        "↓" to "Swipe down to roll under a roof or ramp",
        "✦" to "Aimed at a coin line, the same swipes collect instead of dodge",
        "⏸" to "Tap the clock in the corner to stop; the match resumes where it was"
    )

    override val keyLegend = listOf(
        "A/D or ←/→ for lanes",
        "W, ↑ or Space to jump",
        "S or ↓ to roll",
        "gamepad: A jumps, B rolls, stick changes lane"
    )
}
