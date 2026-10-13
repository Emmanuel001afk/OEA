package com.oea.games.chess

/**
 * Complete chess rules: legal move generation (castling, en passant,
 * promotion), check/checkmate/stalemate, threefold repetition (Zobrist
 * hashing), the fifty-move rule, insufficient material, FEN, SAN, UCI and
 * PGN. Pure Kotlin with no Android dependency, verified with perft.
 *
 * Squares: 0 = a1 … 7 = h1, 56 = a8 … 63 = h8.
 */
object Piece {
    const val EMPTY = 0
    const val PAWN = 1
    const val KNIGHT = 2
    const val BISHOP = 3
    const val ROOK = 4
    const val QUEEN = 5
    const val KING = 6
    const val WHITE = 0
    const val BLACK = 1

    fun type(p: Int) = p and 7
    fun color(p: Int) = p shr 3
    fun make(color: Int, type: Int) = (color shl 3) or type
    fun letter(type: Int) = " PNBRQK"[type]
    fun fenChar(p: Int): Char { val c = letter(type(p)); return if (color(p) == WHITE) c else c.lowercaseChar() }
    fun fromFenChar(c: Char): Int {
        val t = " PNBRQK".indexOf(c.uppercaseChar())
        if (t <= 0) return EMPTY
        return make(if (c.isUpperCase()) WHITE else BLACK, t)
    }
    val VALUES = intArrayOf(0, 100, 320, 330, 500, 900, 20000)
}

/** Moves are packed Ints: from (6 bits) | to (6) | promotion type (3) | flags. */
object Move {
    const val CAPTURE = 1 shl 15
    const val EN_PASSANT = 1 shl 16
    const val CASTLE = 1 shl 17
    const val DOUBLE_PUSH = 1 shl 18
    const val NONE = 0

    fun of(from: Int, to: Int, promo: Int = 0, flags: Int = 0) = from or (to shl 6) or (promo shl 12) or flags
    fun from(m: Int) = m and 63
    fun to(m: Int) = (m shr 6) and 63
    fun promo(m: Int) = (m shr 12) and 7
    fun isCapture(m: Int) = m and CAPTURE != 0
    fun isCastle(m: Int) = m and CASTLE != 0
    fun isEnPassant(m: Int) = m and EN_PASSANT != 0

    fun square(sq: Int) = "${'a' + (sq and 7)}${(sq shr 3) + 1}"
    fun parseSquare(s: String): Int {
        if (s.length != 2) return -1
        val f = s[0] - 'a'; val r = s[1] - '1'
        return if (f in 0..7 && r in 0..7) r * 8 + f else -1
    }
    fun uci(m: Int): String = square(from(m)) + square(to(m)) + (if (promo(m) != 0) Piece.letter(promo(m)).lowercaseChar().toString() else "")
}

enum class GameResult(val pgn: String) { ONGOING("*"), WHITE_WINS("1-0"), BLACK_WINS("0-1"), DRAW("1/2-1/2") }

enum class EndReason(val label: String) {
    NONE(""),
    CHECKMATE("Checkmate"),
    STALEMATE("Stalemate"),
    REPETITION("Draw by threefold repetition"),
    FIFTY_MOVES("Draw by the fifty-move rule"),
    MATERIAL("Draw by insufficient material"),
    RESIGNATION("Resignation"),
    TIMEOUT("Time out"),
    TIMEOUT_DRAW("Time out against insufficient material"),
    AGREEMENT("Draw by agreement"),
    ABANDONED("Opponent left"),
}

class Board {
    val squares = IntArray(64)
    var side = Piece.WHITE
        private set
    /** Bits: 1 white king-side, 2 white queen-side, 4 black king-side, 8 black queen-side. */
    var castling = 0
        private set
    /** En passant target square or -1. */
    var ep = -1
        private set
    var halfmove = 0
        private set
    var fullmove = 1
        private set
    var hash = 0L
        private set
    val kingSquare = IntArray(2)

    private var hMove = IntArray(512)
    private var hCaptured = IntArray(512)
    private var hCastling = IntArray(512)
    private var hEp = IntArray(512)
    private var hHalf = IntArray(512)
    private var hHash = LongArray(512)
    /** Number of moves made since the starting position (for undo and repetition). */
    var ply = 0
        private set
    /** Hashes of positions before the initial setup (e.g. a game loaded mid-way) are not tracked. */
    private var historyBase = 0

    init { reset() }

    fun reset() = setFen(START_FEN)

    /** Independent copy including move history (so repetition rules keep working, e.g. for the AI). */
    fun copy(): Board {
        val b = Board()
        squares.copyInto(b.squares)
        b.side = side; b.castling = castling; b.ep = ep; b.halfmove = halfmove; b.fullmove = fullmove; b.hash = hash
        kingSquare.copyInto(b.kingSquare)
        b.ensureHistory(ply + 1)
        hMove.copyInto(b.hMove, 0, 0, ply); hCaptured.copyInto(b.hCaptured, 0, 0, ply); hCastling.copyInto(b.hCastling, 0, 0, ply)
        hEp.copyInto(b.hEp, 0, 0, ply); hHalf.copyInto(b.hHalf, 0, 0, ply); hHash.copyInto(b.hHash, 0, 0, ply)
        b.ply = ply
        b.historyBase = ply
        return b
    }

    // ---------------------------------------------------------------- FEN

    fun setFen(fen: String): Boolean {
        val parts = fen.trim().split(Regex("\\s+"))
        if (parts.size < 2) return false
        val sq = IntArray(64)
        var rank = 7; var file = 0
        for (c in parts[0]) {
            when {
                c == '/' -> { rank--; file = 0 }
                c.isDigit() -> file += c - '0'
                else -> {
                    if (rank < 0 || file > 7) return false
                    val p = Piece.fromFenChar(c)
                    if (p == Piece.EMPTY) return false
                    sq[rank * 8 + file] = p
                    file++
                }
            }
        }
        sq.copyInto(squares)
        side = if (parts[1] == "b") Piece.BLACK else Piece.WHITE
        castling = 0
        val c = parts.getOrElse(2) { "-" }
        if ('K' in c) castling = castling or 1
        if ('Q' in c) castling = castling or 2
        if ('k' in c) castling = castling or 4
        if ('q' in c) castling = castling or 8
        ep = Move.parseSquare(parts.getOrElse(3) { "-" })
        halfmove = parts.getOrNull(4)?.toIntOrNull() ?: 0
        fullmove = parts.getOrNull(5)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        kingSquare[0] = squares.indexOfFirst { it == Piece.make(Piece.WHITE, Piece.KING) }
        kingSquare[1] = squares.indexOfFirst { it == Piece.make(Piece.BLACK, Piece.KING) }
        if (kingSquare[0] < 0 || kingSquare[1] < 0) return false
        ply = 0
        historyBase = 0
        hash = computeHash()
        return true
    }

    fun fen(): String {
        val sb = StringBuilder()
        for (rank in 7 downTo 0) {
            var empty = 0
            for (file in 0 until 8) {
                val p = squares[rank * 8 + file]
                if (p == Piece.EMPTY) empty++ else {
                    if (empty > 0) { sb.append(empty); empty = 0 }
                    sb.append(Piece.fenChar(p))
                }
            }
            if (empty > 0) sb.append(empty)
            if (rank > 0) sb.append('/')
        }
        sb.append(if (side == Piece.WHITE) " w " else " b ")
        val c = buildString {
            if (castling and 1 != 0) append('K'); if (castling and 2 != 0) append('Q')
            if (castling and 4 != 0) append('k'); if (castling and 8 != 0) append('q')
        }
        sb.append(c.ifEmpty { "-" }).append(' ')
        sb.append(if (ep >= 0) Move.square(ep) else "-")
        sb.append(' ').append(halfmove).append(' ').append(fullmove)
        return sb.toString()
    }

    private fun computeHash(): Long {
        var h = 0L
        for (s in 0 until 64) if (squares[s] != Piece.EMPTY) h = h xor Zobrist.piece[squares[s]][s]
        h = h xor Zobrist.castling[castling]
        if (ep >= 0) h = h xor Zobrist.epFile[ep and 7]
        if (side == Piece.BLACK) h = h xor Zobrist.side
        return h
    }

    // ---------------------------------------------------------------- attacks

    fun isAttacked(s: Int, by: Int): Boolean {
        val file = s and 7
        if (by == Piece.WHITE) {
            if (file > 0 && s - 9 >= 0 && squares[s - 9] == WP) return true
            if (file < 7 && s - 7 >= 0 && squares[s - 7] == WP) return true
        } else {
            if (file > 0 && s + 7 < 64 && squares[s + 7] == BP) return true
            if (file < 7 && s + 9 < 64 && squares[s + 9] == BP) return true
        }
        val knight = Piece.make(by, Piece.KNIGHT)
        for (t in KNIGHT_TARGETS[s]) if (squares[t] == knight) return true
        val king = Piece.make(by, Piece.KING)
        for (t in KING_TARGETS[s]) if (squares[t] == king) return true
        val bishop = Piece.make(by, Piece.BISHOP); val rook = Piece.make(by, Piece.ROOK); val queen = Piece.make(by, Piece.QUEEN)
        for (d in 0 until 8) {
            val diag = d >= 4
            for (t in RAYS[d][s]) {
                val p = squares[t]
                if (p == Piece.EMPTY) continue
                if (p == queen || (diag && p == bishop) || (!diag && p == rook)) return true
                break
            }
        }
        return false
    }

    fun inCheck(color: Int = side): Boolean = isAttacked(kingSquare[color], color xor 1)

    // ---------------------------------------------------------------- generation

    /** Pseudo-legal moves into [out]; returns the count. */
    fun generate(out: IntArray, capturesOnly: Boolean = false): Int {
        var n = 0
        val us = side; val them = us xor 1
        for (s in 0 until 64) {
            val p = squares[s]
            if (p == Piece.EMPTY || Piece.color(p) != us) continue
            when (Piece.type(p)) {
                Piece.PAWN -> n = pawnMoves(s, us, out, n, capturesOnly)
                Piece.KNIGHT -> for (t in KNIGHT_TARGETS[s]) n = addIfOk(s, t, them, out, n, capturesOnly)
                Piece.KING -> {
                    for (t in KING_TARGETS[s]) n = addIfOk(s, t, them, out, n, capturesOnly)
                    if (!capturesOnly) n = castleMoves(us, out, n)
                }
                else -> {
                    val type = Piece.type(p)
                    val from = if (type == Piece.BISHOP) 4 else 0
                    val to = if (type == Piece.ROOK) 4 else 8
                    for (d in from until to) {
                        for (t in RAYS[d][s]) {
                            val q = squares[t]
                            if (q == Piece.EMPTY) { if (!capturesOnly) out[n++] = Move.of(s, t) }
                            else { if (Piece.color(q) == them) out[n++] = Move.of(s, t, 0, Move.CAPTURE); break }
                        }
                    }
                }
            }
        }
        return n
    }

    private fun addIfOk(s: Int, t: Int, them: Int, out: IntArray, n: Int, capturesOnly: Boolean): Int {
        val q = squares[t]
        return when {
            q == Piece.EMPTY -> if (capturesOnly) n else { out[n] = Move.of(s, t); n + 1 }
            Piece.color(q) == them -> { out[n] = Move.of(s, t, 0, Move.CAPTURE); n + 1 }
            else -> n
        }
    }

    private fun pawnMoves(s: Int, us: Int, out: IntArray, start: Int, capturesOnly: Boolean): Int {
        var n = start
        val dir = if (us == Piece.WHITE) 8 else -8
        val startRank = if (us == Piece.WHITE) 1 else 6
        val lastRank = if (us == Piece.WHITE) 7 else 0
        val file = s and 7
        val one = s + dir
        fun push(from: Int, to: Int, flags: Int) {
            if ((to shr 3) == lastRank) {
                for (pr in intArrayOf(Piece.QUEEN, Piece.KNIGHT, Piece.ROOK, Piece.BISHOP)) out[n++] = Move.of(from, to, pr, flags)
            } else out[n++] = Move.of(from, to, 0, flags)
        }
        if (one in 0..63 && squares[one] == Piece.EMPTY) {
            // Promotions count as "captures" for quiescence (they change material a lot).
            if (!capturesOnly || (one shr 3) == lastRank) push(s, one, 0)
            if (!capturesOnly && (s shr 3) == startRank && squares[one + dir] == Piece.EMPTY) out[n++] = Move.of(s, one + dir, 0, Move.DOUBLE_PUSH)
        }
        for (df in intArrayOf(-1, 1)) {
            val f = file + df
            if (f < 0 || f > 7) continue
            val t = one + df
            if (t !in 0..63) continue
            val q = squares[t]
            if (q != Piece.EMPTY && Piece.color(q) != us) push(s, t, Move.CAPTURE)
            else if (t == ep && q == Piece.EMPTY) out[n++] = Move.of(s, t, 0, Move.CAPTURE or Move.EN_PASSANT)
        }
        return n
    }

    private fun castleMoves(us: Int, out: IntArray, start: Int): Int {
        var n = start
        val them = us xor 1
        if (us == Piece.WHITE) {
            if (castling and 1 != 0 && squares[5] == 0 && squares[6] == 0 && squares[7] == WR && !isAttacked(4, them) && !isAttacked(5, them) && !isAttacked(6, them))
                out[n++] = Move.of(4, 6, 0, Move.CASTLE)
            if (castling and 2 != 0 && squares[3] == 0 && squares[2] == 0 && squares[1] == 0 && squares[0] == WR && !isAttacked(4, them) && !isAttacked(3, them) && !isAttacked(2, them))
                out[n++] = Move.of(4, 2, 0, Move.CASTLE)
        } else {
            if (castling and 4 != 0 && squares[61] == 0 && squares[62] == 0 && squares[63] == BR && !isAttacked(60, them) && !isAttacked(61, them) && !isAttacked(62, them))
                out[n++] = Move.of(60, 62, 0, Move.CASTLE)
            if (castling and 8 != 0 && squares[59] == 0 && squares[58] == 0 && squares[57] == 0 && squares[56] == BR && !isAttacked(60, them) && !isAttacked(59, them) && !isAttacked(58, them))
                out[n++] = Move.of(60, 58, 0, Move.CASTLE)
        }
        return n
    }

    /** All legal moves. */
    fun legalMoves(): IntArray {
        val buf = IntArray(256)
        val n = generate(buf)
        val out = IntArray(n)
        var k = 0
        for (i in 0 until n) if (make(buf[i])) { unmake(); out[k++] = buf[i] }
        return out.copyOf(k)
    }

    fun hasLegalMove(): Boolean {
        val buf = IntArray(256)
        val n = generate(buf)
        for (i in 0 until n) if (make(buf[i])) { unmake(); return true }
        return false
    }

    // ---------------------------------------------------------------- make / unmake

    private fun ensureHistory(n: Int) {
        if (n < hMove.size) return
        val size = maxOf(n + 1, hMove.size * 2)
        hMove = hMove.copyOf(size); hCaptured = hCaptured.copyOf(size); hCastling = hCastling.copyOf(size)
        hEp = hEp.copyOf(size); hHalf = hHalf.copyOf(size); hHash = hHash.copyOf(size)
    }

    /** Plays [m]; returns false (and leaves the board unchanged) if it would leave the king in check. */
    fun make(m: Int): Boolean {
        ensureHistory(ply + 1)
        val from = Move.from(m); val to = Move.to(m)
        val piece = squares[from]
        val us = side; val them = us xor 1
        var captured = squares[to]
        hMove[ply] = m; hCastling[ply] = castling; hEp[ply] = ep; hHalf[ply] = halfmove; hHash[ply] = hash
        var h = hash
        if (ep >= 0) h = h xor Zobrist.epFile[ep and 7]
        h = h xor Zobrist.castling[castling]
        // Remove the moving piece.
        h = h xor Zobrist.piece[piece][from]
        squares[from] = Piece.EMPTY
        if (Move.isEnPassant(m)) {
            val capSq = if (us == Piece.WHITE) to - 8 else to + 8
            captured = squares[capSq]
            squares[capSq] = Piece.EMPTY
            h = h xor Zobrist.piece[captured][capSq]
        } else if (captured != Piece.EMPTY) {
            h = h xor Zobrist.piece[captured][to]
        }
        val placed = if (Move.promo(m) != 0) Piece.make(us, Move.promo(m)) else piece
        squares[to] = placed
        h = h xor Zobrist.piece[placed][to]
        if (Move.isCastle(m)) {
            val (rf, rt) = when (to) { 6 -> 7 to 5; 2 -> 0 to 3; 62 -> 63 to 61; else -> 56 to 59 }
            val rook = squares[rf]
            squares[rf] = Piece.EMPTY; squares[rt] = rook
            h = h xor Zobrist.piece[rook][rf] xor Zobrist.piece[rook][rt]
        }
        if (Piece.type(piece) == Piece.KING) kingSquare[us] = to
        castling = castling and CASTLE_MASK[from] and CASTLE_MASK[to]
        h = h xor Zobrist.castling[castling]
        ep = -1
        if (m and Move.DOUBLE_PUSH != 0) {
            // Only record en passant when an enemy pawn can actually capture (cleaner repetition detection).
            val f = to and 7
            val enemyPawn = Piece.make(them, Piece.PAWN)
            if ((f > 0 && squares[to - 1] == enemyPawn) || (f < 7 && squares[to + 1] == enemyPawn)) {
                ep = (from + to) / 2
                h = h xor Zobrist.epFile[ep and 7]
            }
        }
        halfmove = if (Piece.type(piece) == Piece.PAWN || captured != Piece.EMPTY) 0 else halfmove + 1
        if (us == Piece.BLACK) fullmove++
        side = them
        h = h xor Zobrist.side
        hash = h
        hCaptured[ply] = captured
        ply++
        if (isAttacked(kingSquare[us], them)) { unmake(); return false }
        return true
    }

    fun unmake() {
        if (ply <= historyBase) return
        ply--
        val m = hMove[ply]
        val from = Move.from(m); val to = Move.to(m)
        side = side xor 1
        val us = side
        val moved = squares[to]
        val original = if (Move.promo(m) != 0) Piece.make(us, Piece.PAWN) else moved
        squares[from] = original
        squares[to] = Piece.EMPTY
        val captured = hCaptured[ply]
        if (Move.isEnPassant(m)) {
            squares[if (us == Piece.WHITE) to - 8 else to + 8] = captured
        } else {
            squares[to] = captured
        }
        if (Move.isCastle(m)) {
            val (rf, rt) = when (to) { 6 -> 7 to 5; 2 -> 0 to 3; 62 -> 63 to 61; else -> 56 to 59 }
            squares[rf] = squares[rt]; squares[rt] = Piece.EMPTY
        }
        if (Piece.type(original) == Piece.KING) kingSquare[us] = from
        castling = hCastling[ply]; ep = hEp[ply]; halfmove = hHalf[ply]; hash = hHash[ply]
        if (us == Piece.BLACK) fullmove--
    }

    /** Passes the move (null move) for search; undo with [unmakeNull]. */
    fun makeNull() {
        ensureHistory(ply + 1)
        hMove[ply] = Move.NONE; hCastling[ply] = castling; hEp[ply] = ep; hHalf[ply] = halfmove; hHash[ply] = hash; hCaptured[ply] = 0
        var h = hash
        if (ep >= 0) h = h xor Zobrist.epFile[ep and 7]
        ep = -1
        side = side xor 1
        hash = h xor Zobrist.side
        ply++
    }

    fun unmakeNull() {
        ply--
        side = side xor 1
        castling = hCastling[ply]; ep = hEp[ply]; halfmove = hHalf[ply]; hash = hHash[ply]
    }

    /** Moves played so far (oldest first). */
    fun history(): IntArray = hMove.copyOf(ply)
    fun lastMove(): Int = if (ply > 0) hMove[ply - 1] else Move.NONE
    fun capturedAt(index: Int): Int = hCaptured[index]

    // ---------------------------------------------------------------- game state

    /** How often the current position occurred (including now), since the last irreversible move. */
    fun repetitions(): Int {
        var count = 1
        var i = ply - 2
        val limit = maxOf(0, ply - halfmove)
        while (i >= limit) {
            if (hHash[i] == hash) count++
            i -= 2
        }
        return count
    }

    /** True if a position repeated within the search window (search draw detection). */
    fun isRepeatedOnce(): Boolean {
        var i = ply - 2
        val limit = maxOf(0, ply - halfmove)
        while (i >= limit) { if (hHash[i] == hash) return true; i -= 2 }
        return false
    }

    fun insufficientMaterial(): Boolean {
        var minors = 0
        var whiteBishopsLight = 0; var whiteBishopsDark = 0; var blackBishopsLight = 0; var blackBishopsDark = 0
        var knights = 0
        for (s in 0 until 64) {
            val p = squares[s]
            when (Piece.type(p)) {
                Piece.EMPTY, Piece.KING -> Unit
                Piece.PAWN, Piece.ROOK, Piece.QUEEN -> return false
                Piece.KNIGHT -> { knights++; minors++ }
                Piece.BISHOP -> {
                    minors++
                    val light = ((s shr 3) + (s and 7)) % 2 == 1
                    if (Piece.color(p) == Piece.WHITE) { if (light) whiteBishopsLight++ else whiteBishopsDark++ }
                    else { if (light) blackBishopsLight++ else blackBishopsDark++ }
                }
            }
        }
        if (minors <= 1) return true
        // Only bishops, all on the same colour: no mate possible.
        if (knights == 0) {
            val light = whiteBishopsLight + blackBishopsLight
            val dark = whiteBishopsDark + blackBishopsDark
            if (light == 0 || dark == 0) return true
        }
        return false
    }

    /** Whether [color] still has material that could, in principle, deliver mate. */
    fun canMate(color: Int): Boolean {
        var knights = 0; var bishops = 0
        for (p in squares) {
            if (p == Piece.EMPTY || Piece.color(p) != color) continue
            when (Piece.type(p)) {
                Piece.PAWN, Piece.ROOK, Piece.QUEEN -> return true
                Piece.KNIGHT -> knights++
                Piece.BISHOP -> bishops++
            }
        }
        return bishops >= 2 || (bishops >= 1 && knights >= 1) || knights >= 2
    }

    data class Status(val result: GameResult, val reason: EndReason)

    fun status(): Status {
        if (!hasLegalMove()) {
            return if (inCheck()) Status(if (side == Piece.WHITE) GameResult.BLACK_WINS else GameResult.WHITE_WINS, EndReason.CHECKMATE)
            else Status(GameResult.DRAW, EndReason.STALEMATE)
        }
        if (insufficientMaterial()) return Status(GameResult.DRAW, EndReason.MATERIAL)
        if (halfmove >= 100) return Status(GameResult.DRAW, EndReason.FIFTY_MOVES)
        if (repetitions() >= 3) return Status(GameResult.DRAW, EndReason.REPETITION)
        return Status(GameResult.ONGOING, EndReason.NONE)
    }

    // ---------------------------------------------------------------- notation

    /** Standard algebraic notation for a legal move in the current position. */
    fun san(m: Int): String {
        val from = Move.from(m); val to = Move.to(m)
        val piece = squares[from]
        val type = Piece.type(piece)
        val sb = StringBuilder()
        if (Move.isCastle(m)) {
            sb.append(if ((to and 7) == 6) "O-O" else "O-O-O")
        } else {
            if (type != Piece.PAWN) {
                sb.append(Piece.letter(type))
                val others = legalMoves().filter { o -> o != m && Move.to(o) == to && squares[Move.from(o)] == piece }
                if (others.isNotEmpty()) {
                    val sameFile = others.any { (Move.from(it) and 7) == (from and 7) }
                    val sameRank = others.any { (Move.from(it) shr 3) == (from shr 3) }
                    when {
                        !sameFile -> sb.append('a' + (from and 7))
                        !sameRank -> sb.append('1' + (from shr 3))
                        else -> sb.append(Move.square(from))
                    }
                }
            } else if (Move.isCapture(m)) {
                sb.append('a' + (from and 7))
            }
            if (Move.isCapture(m)) sb.append('x')
            sb.append(Move.square(to))
            if (Move.promo(m) != 0) sb.append('=').append(Piece.letter(Move.promo(m)))
        }
        if (make(m)) {
            if (inCheck()) sb.append(if (hasLegalMove()) '+' else '#')
            unmake()
        }
        return sb.toString()
    }

    /** Parses SAN ("Nf3", "exd5", "e8=Q+", "O-O") or UCI ("e2e4"); null if not a legal move. */
    fun parseMove(text: String): Int? {
        val t = text.trim().replace("0-0-0", "O-O-O").replace("0-0", "O-O")
        if (t.isEmpty()) return null
        val legal = legalMoves()
        if (Regex("^[a-h][1-8][a-h][1-8][qrbnQRBN]?$").matches(t)) {
            val from = Move.parseSquare(t.substring(0, 2)); val to = Move.parseSquare(t.substring(2, 4))
            val promo = if (t.length == 5) " PNBRQK".indexOf(t[4].uppercaseChar()) else 0
            return legal.firstOrNull { Move.from(it) == from && Move.to(it) == to && Move.promo(it) == promo }
        }
        fun norm(s: String) = s.replace(Regex("[+#!?x=]"), "")
        val target = norm(t)
        return legal.firstOrNull { norm(san(it)) == target }
    }

    fun perft(depth: Int): Long {
        if (depth == 0) return 1
        val buf = IntArray(256)
        val n = generate(buf)
        var nodes = 0L
        for (i in 0 until n) {
            if (!make(buf[i])) continue
            nodes += perft(depth - 1)
            unmake()
        }
        return nodes
    }

    companion object {
        const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        private val WP = Piece.make(Piece.WHITE, Piece.PAWN)
        private val BP = Piece.make(Piece.BLACK, Piece.PAWN)
        private val WR = Piece.make(Piece.WHITE, Piece.ROOK)
        private val BR = Piece.make(Piece.BLACK, Piece.ROOK)

        /** Castling rights kept when a piece moves from / to each square. */
        private val CASTLE_MASK = IntArray(64) { 15 }.also { m ->
            m[0] = 15 and 2.inv(); m[7] = 15 and 1.inv(); m[4] = 15 and 3.inv()
            m[56] = 15 and 8.inv(); m[63] = 15 and 4.inv(); m[60] = 15 and 12.inv()
        }

        val KNIGHT_TARGETS: Array<IntArray> = Array(64) { s -> targets(s, listOf(1 to 2, 2 to 1, 2 to -1, 1 to -2, -1 to -2, -2 to -1, -2 to 1, -1 to 2)) }
        val KING_TARGETS: Array<IntArray> = Array(64) { s -> targets(s, listOf(1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1)) }

        /** Rays: 0..3 orthogonal (N, S, E, W), 4..7 diagonal (NE, NW, SE, SW). */
        val RAYS: Array<Array<IntArray>> = run {
            val dirs = listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0, 1 to 1, -1 to 1, 1 to -1, -1 to -1)
            Array(8) { d ->
                Array(64) { s ->
                    val out = ArrayList<Int>()
                    var f = (s and 7) + dirs[d].first; var r = (s shr 3) + dirs[d].second
                    while (f in 0..7 && r in 0..7) { out += r * 8 + f; f += dirs[d].first; r += dirs[d].second }
                    out.toIntArray()
                }
            }
        }

        private fun targets(s: Int, deltas: List<Pair<Int, Int>>): IntArray {
            val f = s and 7; val r = s shr 3
            return deltas.mapNotNull { (df, dr) -> val nf = f + df; val nr = r + dr; if (nf in 0..7 && nr in 0..7) nr * 8 + nf else null }.toIntArray()
        }
    }
}

/** Deterministic Zobrist keys (SplitMix64), identical on every device. */
internal object Zobrist {
    private var seed = 0x0EA0C4E55L
    private fun next(): Long {
        seed += -0x61c8864680b583ebL
        var z = seed
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }
    val piece: Array<LongArray> = Array(16) { LongArray(64) { next() } }
    val castling: LongArray = LongArray(16) { next() }
    val epFile: LongArray = LongArray(8) { next() }
    val side: Long = next()
}

/** A complete game record: start position, moves, players and result. */
class ChessGame(startFen: String = Board.START_FEN) {
    val board = Board()
    var startFen: String = startFen
        private set
    val sanMoves = ArrayList<String>()
    var result = GameResult.ONGOING
    var reason = EndReason.NONE
    var whiteName = "White"
    var blackName = "Black"
    var timeControl = "-"

    init { board.setFen(startFen) }

    val isOver get() = result != GameResult.ONGOING

    /** Plays [m] if legal, updating notation and result. */
    fun play(m: Int): Boolean {
        if (isOver) return false
        val legal = board.legalMoves()
        if (m !in legal) return false
        val san = board.san(m)
        board.make(m)
        sanMoves += san
        val s = board.status()
        if (s.result != GameResult.ONGOING) { result = s.result; reason = s.reason }
        return true
    }

    fun undo(): Boolean {
        if (board.ply == 0) return false
        board.unmake()
        if (sanMoves.isNotEmpty()) sanMoves.removeAt(sanMoves.lastIndex)
        result = GameResult.ONGOING; reason = EndReason.NONE
        return true
    }

    fun end(result: GameResult, reason: EndReason) { this.result = result; this.reason = reason }

    fun restart(fen: String = Board.START_FEN) {
        startFen = fen
        board.setFen(fen)
        sanMoves.clear()
        result = GameResult.ONGOING; reason = EndReason.NONE
    }

    fun uciMoves(): List<String> = board.history().map { Move.uci(it) }

    fun pgn(date: String, event: String = "OEA Chess", site: String = "OEA"): String {
        val sb = StringBuilder()
        fun tag(k: String, v: String) = sb.append('[').append(k).append(" \"").append(v.replace("\"", "'")).append("\"]\n")
        tag("Event", event); tag("Site", site); tag("Date", date); tag("Round", "-")
        tag("White", whiteName); tag("Black", blackName); tag("Result", result.pgn)
        if (timeControl != "-") tag("TimeControl", timeControl)
        if (reason != EndReason.NONE) tag("Termination", reason.label)
        if (startFen != Board.START_FEN) { tag("SetUp", "1"); tag("FEN", startFen) }
        sb.append('\n')
        val tmp = Board().also { it.setFen(startFen) }
        var moveNo = tmp.fullmove
        var whiteToMove = tmp.side == Piece.WHITE
        val line = StringBuilder()
        sanMoves.forEachIndexed { i, san ->
            if (whiteToMove) line.append("$moveNo. ") else if (i == 0) line.append("$moveNo... ")
            line.append(san).append(' ')
            if (!whiteToMove) moveNo++
            whiteToMove = !whiteToMove
        }
        line.append(result.pgn)
        // Wrap at ~80 columns like most PGN writers.
        var col = 0
        for (word in line.split(' ')) {
            if (col + word.length > 79) { sb.append('\n'); col = 0 } else if (col > 0) { sb.append(' '); col++ }
            sb.append(word); col += word.length
        }
        sb.append('\n')
        return sb.toString()
    }

    companion object {
        /** Loads the main line of a PGN (comments, variations and NAGs are ignored). */
        fun fromPgn(pgn: String): ChessGame? {
            val tags = Regex("\\[(\\w+)\\s+\"([^\"]*)\"]").findAll(pgn).associate { it.groupValues[1] to it.groupValues[2] }
            val game = ChessGame(tags["FEN"] ?: Board.START_FEN)
            tags["White"]?.let { game.whiteName = it }
            tags["Black"]?.let { game.blackName = it }
            var body = pgn.replace(Regex("\\[[^]]*]"), " ")
            body = body.replace(Regex("\\{[^}]*}"), " ").replace(Regex(";[^\\n]*"), " ")
            var depth = 0
            val stripped = StringBuilder()
            for (c in body) { if (c == '(') depth++ else if (c == ')') depth-- else if (depth == 0) stripped.append(c) }
            for (token in stripped.split(Regex("\\s+"))) {
                val tk = token.replace(Regex("^\\d+\\.+"), "").trim()
                if (tk.isEmpty() || tk.startsWith("$")) continue
                when (tk) {
                    "1-0" -> { game.end(GameResult.WHITE_WINS, EndReason.NONE); break }
                    "0-1" -> { game.end(GameResult.BLACK_WINS, EndReason.NONE); break }
                    "1/2-1/2" -> { game.end(GameResult.DRAW, EndReason.AGREEMENT); break }
                    "*" -> break
                }
                val m = game.board.parseMove(tk) ?: return null
                game.play(m)
            }
            return game
        }
    }
}
