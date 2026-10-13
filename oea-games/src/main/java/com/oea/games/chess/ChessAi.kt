package com.oea.games.chess

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Chess engine: iterative deepening negamax with alpha-beta, principal
 * variation search, transposition table, quiescence search, null-move
 * pruning, late-move reductions, killer/history move ordering, check
 * extensions and a tapered evaluation (material, piece-square tables, pawn
 * structure, bishop pair, rooks on open files, king safety).
 *
 * Ten difficulty levels range from a beginner who blunders to a strong
 * club player limited only by the phone's speed.
 */
class ChessAi(private val ttBits: Int = 18) {
    data class Level(val depth: Int, val timeMs: Long, val noise: Int, val blunder: Float)

    data class Result(val move: Int, val score: Int, val depth: Int, val nodes: Long, val pv: List<Int>)

    private val ttSize = 1 shl ttBits
    private val ttKey = LongArray(ttSize)
    private val ttData = LongArray(ttSize) // move(24) | depth(8) | flag(2) | score(32 signed)
    private val killers = Array(MAX_PLY) { IntArray(2) }
    private val historyTable = Array(2) { Array(64) { IntArray(64) } }
    private val moveBufs = Array(MAX_PLY + 8) { IntArray(256) }
    private val scoreBufs = Array(MAX_PLY + 8) { IntArray(256) }
    private var nodes = 0L
    private var deadline = 0L
    private val stopFlag = AtomicBoolean(false)
    private var aborted = false

    /** Stops a running search from another thread. */
    fun stop() = stopFlag.set(true)

    fun clear() {
        ttKey.fill(0L); ttData.fill(0L)
        killers.forEach { it.fill(0) }
        historyTable.forEach { s -> s.forEach { it.fill(0) } }
    }

    /** Picks a move for the side to move on [board] (the board is not modified). */
    fun choose(board: Board, level: Int, random: Random = Random.Default): Result? {
        val b = board.copy()
        val legal = b.legalMoves()
        if (legal.isEmpty()) return null
        if (legal.size == 1) return Result(legal[0], 0, 0, 0, listOf(legal[0]))
        val lv = levelOf(level)
        if (lv.blunder > 0f && random.nextFloat() < lv.blunder) {
            val m = legal[random.nextInt(legal.size)]
            return Result(m, 0, 0, 0, listOf(m))
        }
        val best = search(b, lv.depth, lv.timeMs) ?: return Result(legal[0], 0, 0, nodes, listOf(legal[0]))
        val opening = b.ply < 6 && level >= 4
        if (lv.noise <= 0 && !opening) return best
        // Weaker levels (and variety in the opening): pick among moves close to the best.
        val margin = if (lv.noise > 0) lv.noise else 18
        val scored = scoreRootMoves(b, legal, max(1, min(best.depth, if (lv.noise > 0) lv.depth else 3)))
        val bestScore = scored.maxOf { it.second }
        val candidates = scored.filter { it.second >= bestScore - margin }
        val pick = candidates[random.nextInt(candidates.size)]
        return Result(pick.first, pick.second, best.depth, nodes, listOf(pick.first))
    }

    /** Iterative deepening to [maxDepth] within [timeMs]. */
    fun search(board: Board, maxDepth: Int, timeMs: Long): Result? {
        stopFlag.set(false)
        aborted = false
        nodes = 0
        deadline = System.currentTimeMillis() + timeMs
        historyTable.forEach { s -> s.forEach { row -> for (i in row.indices) row[i] = row[i] shr 3 } }
        var best: Result? = null
        var alpha = -INF; var beta = INF
        var prevScore = 0
        var depth = 1
        while (depth <= maxDepth) {
            if (depth >= 5) { alpha = prevScore - 40; beta = prevScore + 40 }
            var score = negamax(board, depth, alpha, beta, 0, true)
            if (!aborted && (score <= alpha || score >= beta)) {
                score = negamax(board, depth, -INF, INF, 0, true)
            }
            if (aborted) break
            prevScore = score
            val pv = principalVariation(board, depth)
            if (pv.isNotEmpty()) best = Result(pv[0], score, depth, nodes, pv)
            if (abs(score) > MATE - 200) break
            // Not enough time left for another, deeper iteration.
            if (System.currentTimeMillis() > deadline - (timeMs * 0.55).toLong()) break
            alpha = -INF; beta = INF
            depth++
        }
        return best
    }

    private fun scoreRootMoves(b: Board, legal: IntArray, depth: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        deadline = System.currentTimeMillis() + 2500
        aborted = false
        for (m in legal) {
            b.make(m)
            val s = -negamax(b, depth - 1, -INF, INF, 1, true)
            b.unmake()
            out += m to (if (aborted) -INF else s)
        }
        aborted = false
        return out
    }

    private fun negamax(b: Board, depthIn: Int, alphaIn: Int, beta: Int, ply: Int, allowNull: Boolean): Int {
        if ((++nodes and 2047L) == 0L && (stopFlag.get() || System.currentTimeMillis() > deadline)) aborted = true
        if (aborted) return 0
        var alpha = alphaIn
        val inCheck = b.inCheck()
        var depth = depthIn
        if (inCheck) depth++
        if (ply > 0) {
            if (b.halfmove >= 100 || b.isRepeatedOnce() || b.insufficientMaterial()) return 0
            // Mate distance pruning.
            alpha = max(alpha, -MATE + ply)
            if (alpha >= min(beta, MATE - ply - 1)) return alpha
        }
        if (depth <= 0 || ply >= MAX_PLY - 2) return quiesce(b, alpha, beta, ply)

        val pvNode = beta - alpha > 1
        val idx = (b.hash and (ttSize - 1).toLong()).toInt()
        var ttMove = 0
        if (ttKey[idx] == b.hash) {
            val d = ttData[idx]
            ttMove = (d ushr 40).toInt() and 0xFFFFFF
            val ttDepth = ((d ushr 32) and 0x3F).toInt()
            val flag = ((d ushr 38) and 3).toInt()
            val ttScore = fromTt(d.toInt(), ply)
            if (!pvNode && ttDepth >= depth) {
                if (flag == EXACT) return ttScore
                if (flag == LOWER && ttScore >= beta) return ttScore
                if (flag == UPPER && ttScore <= alpha) return ttScore
            }
        }

        val staticEval = if (inCheck) -INF else evaluate(b)
        // Reverse futility: far above beta at low depth, the opponent won't allow this.
        if (!pvNode && !inCheck && depth <= 3 && staticEval - 120 * depth >= beta && abs(beta) < MATE - 200) return staticEval
        // Null move: if passing still beats beta, the position is too good to need a full search.
        if (allowNull && !pvNode && !inCheck && depth >= 3 && staticEval >= beta && hasPieces(b, b.side)) {
            val r = if (depth > 6) 3 else 2
            b.makeNull()
            val score = -negamax(b, depth - 1 - r, -beta, -beta + 1, ply + 1, false)
            b.unmakeNull()
            if (aborted) return 0
            if (score >= beta) return beta
        }

        val moves = moveBufs[ply]
        val scores = scoreBufs[ply]
        val n = b.generate(moves)
        orderMoves(b, moves, scores, n, ttMove, ply)
        var legal = 0
        var bestScore = -INF
        var bestMove = 0
        val origAlpha = alpha
        for (i in 0 until n) {
            pickNext(moves, scores, i, n)
            val m = moves[i]
            if (!b.make(m)) continue
            legal++
            val quiet = !Move.isCapture(m) && Move.promo(m) == 0
            val givesCheck = b.inCheck()
            var score: Int
            if (legal == 1) {
                score = -negamax(b, depth - 1, -beta, -alpha, ply + 1, true)
            } else {
                // Late move reductions for quiet moves ordered late.
                var reduction = 0
                if (depth >= 3 && legal > 3 && quiet && !inCheck && !givesCheck && m != killers[ply][0] && m != killers[ply][1]) {
                    reduction = if (legal > 10) 2 else 1
                    if (pvNode) reduction = max(0, reduction - 1)
                }
                score = -negamax(b, depth - 1 - reduction, -alpha - 1, -alpha, ply + 1, true)
                if (score > alpha && reduction > 0) score = -negamax(b, depth - 1, -alpha - 1, -alpha, ply + 1, true)
                if (score > alpha && score < beta) score = -negamax(b, depth - 1, -beta, -alpha, ply + 1, true)
            }
            b.unmake()
            if (aborted) return 0
            if (score > bestScore) {
                bestScore = score
                bestMove = m
                if (score > alpha) {
                    alpha = score
                    if (score >= beta) {
                        if (quiet) {
                            if (killers[ply][0] != m) { killers[ply][1] = killers[ply][0]; killers[ply][0] = m }
                            val h = historyTable[b.side][Move.from(m)]
                            h[Move.to(m)] = min(h[Move.to(m)] + depth * depth, 1_000_000)
                        }
                        break
                    }
                }
            }
        }
        if (legal == 0) return if (inCheck) -MATE + ply else 0
        val flag = when {
            bestScore >= beta -> LOWER
            bestScore > origAlpha -> EXACT
            else -> UPPER
        }
        store(b.hash, bestMove, depth, flag, toTt(bestScore, ply))
        return bestScore
    }

    private fun quiesce(b: Board, alphaIn: Int, beta: Int, ply: Int): Int {
        if ((++nodes and 2047L) == 0L && (stopFlag.get() || System.currentTimeMillis() > deadline)) aborted = true
        if (aborted) return 0
        var alpha = alphaIn
        val standPat = evaluate(b)
        if (standPat >= beta) return standPat
        if (standPat > alpha) alpha = standPat
        if (ply >= MAX_PLY - 2) return standPat
        val moves = moveBufs[ply]
        val scores = scoreBufs[ply]
        val n = b.generate(moves, capturesOnly = true)
        orderMoves(b, moves, scores, n, 0, ply)
        for (i in 0 until n) {
            pickNext(moves, scores, i, n)
            val m = moves[i]
            // Delta pruning: even winning this piece can't lift us to alpha.
            val victim = if (Move.isEnPassant(m)) Piece.PAWN else Piece.type(b.squares[Move.to(m)])
            if (Move.promo(m) == 0 && standPat + Piece.VALUES[victim] + 200 < alpha) continue
            if (!b.make(m)) continue
            val score = -quiesce(b, -beta, -alpha, ply + 1)
            b.unmake()
            if (aborted) return 0
            if (score >= beta) return score
            if (score > alpha) alpha = score
        }
        return alpha
    }

    private fun orderMoves(b: Board, moves: IntArray, scores: IntArray, n: Int, ttMove: Int, ply: Int) {
        for (i in 0 until n) {
            val m = moves[i]
            scores[i] = when {
                m == ttMove -> 10_000_000
                Move.isCapture(m) -> {
                    val victim = if (Move.isEnPassant(m)) Piece.PAWN else Piece.type(b.squares[Move.to(m)])
                    val attacker = Piece.type(b.squares[Move.from(m)])
                    5_000_000 + Piece.VALUES[victim] * 10 - Piece.VALUES[attacker] / 10 + Move.promo(m) * 1000
                }
                Move.promo(m) != 0 -> 4_500_000 + Move.promo(m)
                ply < MAX_PLY && m == killers[ply][0] -> 4_000_000
                ply < MAX_PLY && m == killers[ply][1] -> 3_900_000
                else -> historyTable[b.side][Move.from(m)][Move.to(m)]
            }
        }
    }

    private fun pickNext(moves: IntArray, scores: IntArray, start: Int, n: Int) {
        var best = start
        for (j in start + 1 until n) if (scores[j] > scores[best]) best = j
        if (best != start) {
            val tm = moves[start]; moves[start] = moves[best]; moves[best] = tm
            val ts = scores[start]; scores[start] = scores[best]; scores[best] = ts
        }
    }

    private fun store(key: Long, move: Int, depth: Int, flag: Int, score: Int) {
        val idx = (key and (ttSize - 1).toLong()).toInt()
        val existing = ttData[idx]
        val existingDepth = ((existing ushr 32) and 0x3F).toInt()
        if (ttKey[idx] == key && existingDepth > depth && flag != EXACT) return
        ttKey[idx] = key
        ttData[idx] = ((move.toLong() and 0xFFFFFF) shl 40) or ((flag.toLong() and 3) shl 38) or ((depth.toLong() and 0x3F) shl 32) or (score.toLong() and 0xFFFFFFFFL)
    }

    private fun toTt(score: Int, ply: Int) = when { score > MATE - 500 -> score + ply; score < -MATE + 500 -> score - ply; else -> score }
    private fun fromTt(score: Int, ply: Int) = when { score > MATE - 500 -> score - ply; score < -MATE + 500 -> score + ply; else -> score }

    private fun principalVariation(board: Board, maxLen: Int): List<Int> {
        val pv = ArrayList<Int>()
        val b = board.copy()
        val seen = HashSet<Long>()
        while (pv.size < maxLen + 4) {
            val idx = (b.hash and (ttSize - 1).toLong()).toInt()
            if (ttKey[idx] != b.hash || !seen.add(b.hash)) break
            val m = (ttData[idx] ushr 40).toInt() and 0xFFFFFF
            if (m == 0 || m !in b.legalMoves()) break
            pv += m
            b.make(m)
        }
        return pv
    }

    private fun hasPieces(b: Board, color: Int): Boolean {
        for (p in b.squares) {
            if (p == Piece.EMPTY || Piece.color(p) != color) continue
            val t = Piece.type(p)
            if (t != Piece.PAWN && t != Piece.KING) return true
        }
        return false
    }

    // ---------------------------------------------------------------- evaluation

    /** Static evaluation from the side to move's point of view (centipawns). */
    fun evaluate(b: Board): Int {
        var mg = 0; var eg = 0; var phase = 0
        val pawnFiles = Array(2) { IntArray(8) }
        val bishops = IntArray(2)
        for (s in 0 until 64) {
            val p = b.squares[s]
            if (p == Piece.EMPTY) continue
            if (Piece.type(p) == Piece.PAWN) pawnFiles[Piece.color(p)][s and 7]++
        }
        for (s in 0 until 64) {
            val p = b.squares[s]
            if (p == Piece.EMPTY) continue
            val c = Piece.color(p)
            val t = Piece.type(p)
            val sign = if (c == Piece.WHITE) 1 else -1
            // Tables are written from White's view with a8 first.
            val idx = if (c == Piece.WHITE) (7 - (s shr 3)) * 8 + (s and 7) else (s shr 3) * 8 + (s and 7)
            val file = s and 7
            val rank = if (c == Piece.WHITE) s shr 3 else 7 - (s shr 3)
            var vMg = Piece.VALUES[t]; var vEg = Piece.VALUES[t]
            when (t) {
                Piece.PAWN -> {
                    vMg += PST_PAWN[idx]; vEg += PST_PAWN[idx] / 2 + rank * 8
                    if (pawnFiles[c][file] > 1) { vMg -= 12; vEg -= 18 }
                    val isolated = (file == 0 || pawnFiles[c][file - 1] == 0) && (file == 7 || pawnFiles[c][file + 1] == 0)
                    if (isolated) { vMg -= 10; vEg -= 14 }
                    if (isPassed(b, s, c)) { vMg += PASSED[rank] / 2; vEg += PASSED[rank] }
                }
                Piece.KNIGHT -> { vMg += PST_KNIGHT[idx]; vEg += PST_KNIGHT[idx]; phase += 1 }
                Piece.BISHOP -> { vMg += PST_BISHOP[idx]; vEg += PST_BISHOP[idx]; phase += 1; bishops[c]++ }
                Piece.ROOK -> {
                    vMg += PST_ROOK[idx]; vEg += PST_ROOK[idx]; phase += 2
                    if (pawnFiles[c][file] == 0) { val open = pawnFiles[c xor 1][file] == 0; vMg += if (open) 22 else 11; vEg += if (open) 12 else 6 }
                }
                Piece.QUEEN -> { vMg += PST_QUEEN[idx]; vEg += PST_QUEEN[idx]; phase += 4 }
                Piece.KING -> {
                    vMg = PST_KING_MG[idx]; vEg = PST_KING_EG[idx]
                    // Pawn shield in front of a castled king.
                    if (rank == 0) {
                        val dir = if (c == Piece.WHITE) 8 else -8
                        for (df in -1..1) {
                            val f = file + df
                            if (f !in 0..7) continue
                            val front = s + dir + df
                            if (front in 0..63 && b.squares[front] == Piece.make(c, Piece.PAWN)) vMg += 12
                        }
                    }
                }
            }
            mg += sign * vMg; eg += sign * vEg
        }
        if (bishops[0] >= 2) { mg += 30; eg += 45 }
        if (bishops[1] >= 2) { mg -= 30; eg -= 45 }
        val ph = min(phase, 24)
        var score = (mg * ph + eg * (24 - ph)) / 24
        score += if (b.side == Piece.WHITE) 10 else -10
        return if (b.side == Piece.WHITE) score else -score
    }

    private fun isPassed(b: Board, s: Int, c: Int): Boolean {
        val file = s and 7
        val enemy = Piece.make(c xor 1, Piece.PAWN)
        var r = (s shr 3) + if (c == Piece.WHITE) 1 else -1
        while (r in 0..7) {
            for (f in max(0, file - 1)..min(7, file + 1)) if (b.squares[r * 8 + f] == enemy) return false
            r += if (c == Piece.WHITE) 1 else -1
        }
        return true
    }

    companion object {
        const val INF = 1_000_000
        const val MATE = 100_000
        const val MAX_PLY = 96
        private const val EXACT = 0
        private const val LOWER = 1
        private const val UPPER = 2

        val LEVEL_NAMES = listOf("Beginner", "Casual", "Novice", "Intermediate", "Club", "Strong club", "Expert", "Master", "Grandmaster", "Maximum")

        fun levelOf(level: Int): Level = when (level.coerceIn(1, 10)) {
            1 -> Level(1, 150, 400, 0.30f)
            2 -> Level(2, 200, 220, 0.12f)
            3 -> Level(2, 300, 120, 0.05f)
            4 -> Level(3, 400, 60, 0.0f)
            5 -> Level(4, 600, 30, 0.0f)
            6 -> Level(5, 900, 0, 0.0f)
            7 -> Level(7, 1400, 0, 0.0f)
            8 -> Level(9, 2200, 0, 0.0f)
            9 -> Level(14, 3800, 0, 0.0f)
            else -> Level(40, 7000, 0, 0.0f)
        }

        private val PASSED = intArrayOf(0, 8, 14, 26, 48, 80, 130, 0)

        private val PST_PAWN = intArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0,
            50, 50, 50, 50, 50, 50, 50, 50,
            10, 10, 20, 30, 30, 20, 10, 10,
            5, 5, 10, 25, 25, 10, 5, 5,
            0, 0, 0, 20, 20, 0, 0, 0,
            5, -5, -10, 0, 0, -10, -5, 5,
            5, 10, 10, -20, -20, 10, 10, 5,
            0, 0, 0, 0, 0, 0, 0, 0,
        )
        private val PST_KNIGHT = intArrayOf(
            -50, -40, -30, -30, -30, -30, -40, -50,
            -40, -20, 0, 0, 0, 0, -20, -40,
            -30, 0, 10, 15, 15, 10, 0, -30,
            -30, 5, 15, 20, 20, 15, 5, -30,
            -30, 0, 15, 20, 20, 15, 0, -30,
            -30, 5, 10, 15, 15, 10, 5, -30,
            -40, -20, 0, 5, 5, 0, -20, -40,
            -50, -40, -30, -30, -30, -30, -40, -50,
        )
        private val PST_BISHOP = intArrayOf(
            -20, -10, -10, -10, -10, -10, -10, -20,
            -10, 0, 0, 0, 0, 0, 0, -10,
            -10, 0, 5, 10, 10, 5, 0, -10,
            -10, 5, 5, 10, 10, 5, 5, -10,
            -10, 0, 10, 10, 10, 10, 0, -10,
            -10, 10, 10, 10, 10, 10, 10, -10,
            -10, 5, 0, 0, 0, 0, 5, -10,
            -20, -10, -10, -10, -10, -10, -10, -20,
        )
        private val PST_ROOK = intArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0,
            5, 10, 10, 10, 10, 10, 10, 5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            0, 0, 0, 5, 5, 0, 0, 0,
        )
        private val PST_QUEEN = intArrayOf(
            -20, -10, -10, -5, -5, -10, -10, -20,
            -10, 0, 0, 0, 0, 0, 0, -10,
            -10, 0, 5, 5, 5, 5, 0, -10,
            -5, 0, 5, 5, 5, 5, 0, -5,
            0, 0, 5, 5, 5, 5, 0, -5,
            -10, 5, 5, 5, 5, 5, 0, -10,
            -10, 0, 5, 0, 0, 0, 0, -10,
            -20, -10, -10, -5, -5, -10, -10, -20,
        )
        private val PST_KING_MG = intArrayOf(
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -20, -30, -30, -40, -40, -30, -30, -20,
            -10, -20, -20, -20, -20, -20, -20, -10,
            20, 20, 0, 0, 0, 0, 20, 20,
            20, 30, 10, 0, 0, 10, 30, 20,
        )
        private val PST_KING_EG = intArrayOf(
            -50, -40, -30, -20, -20, -30, -40, -50,
            -30, -20, -10, 0, 0, -10, -20, -30,
            -30, -10, 20, 30, 30, 20, -10, -30,
            -30, -10, 30, 40, 40, 30, -10, -30,
            -30, -10, 30, 40, 40, 30, -10, -30,
            -30, -10, 20, 30, 30, 20, -10, -30,
            -30, -30, 0, 0, 0, 0, -30, -30,
            -50, -30, -30, -30, -30, -30, -30, -50,
        )
    }
}
