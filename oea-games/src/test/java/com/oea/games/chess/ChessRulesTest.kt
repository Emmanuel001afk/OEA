package com.oea.games.chess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Move generation is verified against the standard perft node counts. */
class ChessRulesTest {
    private fun perft(fen: String, depth: Int): Long = Board().also { assertTrue(it.setFen(fen)) }.perft(depth)

    @Test fun startPosition() = assertEquals(197_281L, perft(Board.START_FEN, 4))
    @Test fun kiwipete() = assertEquals(97_862L, perft("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1", 3))
    @Test fun enPassantAndPins() = assertEquals(43_238L, perft("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1", 4))
    @Test fun promotionsAndCastling() = assertEquals(9_467L, perft("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1", 3))
    @Test fun position5() = assertEquals(62_379L, perft("rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8", 3))

    @Test fun fenRoundTrip() {
        val fen = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"
        assertEquals(fen, Board().also { it.setFen(fen) }.fen())
    }

    @Test fun checkmateAndNotation() {
        val g = ChessGame()
        for (mv in listOf("f3", "e5", "g4", "Qh4#")) assertTrue(g.play(g.board.parseMove(mv)!!))
        assertEquals(GameResult.BLACK_WINS, g.result)
        assertEquals(EndReason.CHECKMATE, g.reason)
        assertEquals("Qh4#", g.sanMoves.last())
    }

    @Test fun stalemate() {
        val g = ChessGame("k7/8/1QK5/8/8/8/8/8 w - - 0 1")
        assertTrue(g.play(g.board.parseMove("b6c7")!!))
        assertEquals(EndReason.STALEMATE, g.reason)
    }

    @Test fun threefoldRepetition() {
        val g = ChessGame()
        repeat(2) { for (mv in listOf("Nf3", "Nf6", "Ng1", "Ng8")) g.play(g.board.parseMove(mv)!!) }
        assertEquals(EndReason.REPETITION, g.reason)
    }

    @Test fun insufficientMaterial() = assertEquals(EndReason.MATERIAL, Board().also { it.setFen("8/8/8/4k3/8/8/2B5/4K3 b - - 0 1") }.status().reason)

    @Test fun pgnRoundTrip() {
        val g = ChessGame()
        for (mv in listOf("e4", "e5", "Nf3", "Nc6", "Bb5", "a6", "Ba4", "Nf6", "O-O", "Be7")) g.play(g.board.parseMove(mv)!!)
        val back = ChessGame.fromPgn(g.pgn("2026.10.13"))
        assertNotNull(back)
        assertEquals(g.sanMoves, back!!.sanMoves)
    }

    @Test fun aiFindsMateInOne() {
        val b = Board().also { it.setFen("r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4") }
        val r = ChessAi(16).search(b, 4, 2000)!!
        assertEquals("h5f7", Move.uci(r.move))
    }
}
