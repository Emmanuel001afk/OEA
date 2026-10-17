package com.oea.standalone.arena

import com.oea.games.OeaStandaloneGameActivity

/**
 * OEA Arena as its own app. Aim controls come from the shared shell — this is the
 * game they were written for — so only the movement half is listed here.
 */
class ArenaActivity : OeaStandaloneGameActivity() {
    override val standaloneGameId: String = "arena"

    override val controlLegend = listOf(
        "◐" to "Left thumb walks: the stick area steers, up is the horizon",
        "◎" to "Right thumb looks: drag anywhere on the right half to aim",
        "✛" to "Tap to fire; hold for a full-auto burst",
        "▲" to "The jump and crouch buttons sit under the aim thumb",
        "⌖" to "Tap the crosshair button to look down the sight — ADS sensitivity applies",
        "⏻" to "Reload, weapon swap and the map live in the corner cluster"
    )
}
