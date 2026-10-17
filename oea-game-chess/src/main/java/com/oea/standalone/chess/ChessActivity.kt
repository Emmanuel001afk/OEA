package com.oea.standalone.chess

import com.oea.games.OeaStandaloneGameActivity

/** OEA Chess as its own app: tap a piece, then the square, or drag it over. */
class ChessActivity : OeaStandaloneGameActivity() {
    override val standaloneGameId: String = "chess"

    override val controlLegend = listOf(
        "○" to "Tap one of your pieces — its legal squares light up",
        "→" to "Tap a lit square to play there, or drag the piece onto it",
        "✥" to "Tap somewhere else, or the piece again, to put it back",
        "⇈" to "A pawn reaching the last rank asks what it becomes",
        "⧉" to "In the 3D board: two fingers orbit, pinch zooms, one finger taps as above"
    )
}
