package com.oea.games.chess

import android.os.SystemClock
import com.oea.engine.audio.Sfx
import com.oea.engine.audio.Sound
import com.oea.engine.core.Choice
import com.oea.engine.core.GameDescriptor
import com.oea.engine.core.GameDimension
import com.oea.engine.core.GameOption
import com.oea.engine.core.GameOrientation
import com.oea.engine.core.GameResult as HostResult
import com.oea.engine.core.Jobs
import com.oea.engine.core.MenuItem
import com.oea.engine.core.OeaGame
import com.oea.engine.core.PlayMode
import com.oea.engine.graphics.Align
import com.oea.engine.graphics.Environment
import com.oea.engine.graphics.Frame
import com.oea.engine.graphics.Material
import com.oea.engine.graphics.Mesh
import com.oea.engine.graphics.MeshBuilder
import com.oea.engine.graphics.PerspectiveCamera
import com.oea.engine.graphics.Textures
import com.oea.engine.graphics.ui
import com.oea.engine.graphics.world
import com.oea.engine.island.IslandStyle
import com.oea.engine.island.LiveActivity
import com.oea.engine.math.Colors
import com.oea.engine.math.Intersect
import com.oea.engine.math.Mat4
import com.oea.engine.math.MathX
import com.oea.engine.math.Ray
import com.oea.engine.net.session.NetSession
import com.oea.engine.ui.ButtonStyle
import com.oea.engine.ui.Hud
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

/**
 * OEA Chess: full rules, ten AI levels, pass-and-play (with auto-flip or a
 * face-to-face layout), Wi-Fi/hotspot and online play, clocks with
 * increment, takebacks, draw offers, hints, PGN export, and both a 3D board
 * (orbit, pinch zoom, drag-and-drop) and a classic 2D board.
 */
class OeaChess : OeaGame() {
    private enum class Controller { LOCAL, AI, REMOTE }

    private class Anim(val piece: Int, val from: Int, val to: Int, val captured: Int, val capturedSq: Int, val rookFrom: Int, val rookTo: Int, val rook: Int) {
        var t = 0f
    }

    private val game = ChessGame()
    private val ai = ChessAi()
    private var aiJob: Jobs.Job? = null
    private var hintJob: Jobs.Job? = null
    private var hintMove = 0
    private var hintUntil = 0L
    private val controllers = arrayOf(Controller.LOCAL, Controller.AI)
    private var level = 5
    private var mode = PlayMode.SOLO
    private var flipMode = "auto"
    private var view3D = true
    /** Colour shown at the bottom of the screen. */
    private var viewColor = Piece.WHITE
    private var clockBase = 0L
    private var clockInc = 0L
    private val remaining = LongArray(2)
    private var clockRunning = -1
    private var clockLast = 0L
    private var lowTimeBeep = -1L

    private var selected = -1
    private var targets = IntArray(0)
    private var anim: Anim? = null
    private var promotionFrom = -1
    private var promotionTo = -1
    private var pressPointer = -1
    private var pressSq = -1
    private var pressX = 0f
    private var pressY = 0f
    private var dragging = false
    private var dragX = 0f
    private var dragY = 0f
    private var orbiting = false
    private var showResult = false
    private var resultShownAt = 0L
    private var status = ""
    private var flash = ""
    private var flashUntil = 0L

    // Networking
    private var net: NetSession? = null
    private var opponentId = -1
    private var opponentAway = false
    private var awaySince = 0L
    private var drawOffered = false

    // Rendering
    private val camera = PerspectiveCamera(45f)
    private val env = Environment.studio().apply {
        sunDirection.set(-0.35f, 0.9f, -0.45f).nor()
        sunIntensity = 2.1f
        ambientIntensity = 0.75f
        shadowDistance = 7.5f
        shadowStrength = 0.6f
        clearColor = 0xFF1A1E25.toInt()
    }
    private var camYaw = MathX.PI_F
    private var camYawTarget = MathX.PI_F
    private var camPitch = 0.98f
    private var camDist = 14.5f
    private val ray = Ray()
    private val m = Mat4()
    private val uv = FloatArray(4)
    private var boardX = 0f
    private var boardY = 0f
    private var boardSize = 0f
    private val tableMesh: Mesh by lazy { MeshBuilder().plane(0f, -0.16f, 0f, 60f, 60f, uvPerUnit = 0.35f).build() }
    private val dotMesh: Mesh by lazy { MeshBuilder().cylinder(0f, 0f, 0f, 0.13f, 0.012f, 24).build() }
    private val ringMesh: Mesh by lazy { MeshBuilder().torus(0f, 0.01f, 0f, 0.4f, 0.035f, 36, 8).build() }
    private val lastMoveMat = Material.unlit(0x66F6E05A).apply { transparent = true; depthWrite = false; fog = false }
    private val selectMat = Material.unlit(0x8846B4FF.toInt()).apply { transparent = true; depthWrite = false; fog = false }
    private val hintMat = Material.unlit(0x8866E08A.toInt()).apply { transparent = true; depthWrite = false; fog = false }
    private val dotMat = Material.unlit(0x55101418).apply { transparent = true; depthWrite = false; fog = false }
    private val checkMat by lazy { Material.unlit(0xCCFF3B30.toInt(), Textures.softCircle(128, 0.2f)).apply { transparent = true; depthWrite = false; fog = false } }

    private lateinit var sMove: Sound
    private lateinit var sCapture: Sound
    private lateinit var sCheck: Sound
    private lateinit var sWin: Sound
    private lateinit var sLose: Sound
    private lateinit var sTick: Sound

    override fun onCreate() {
        val launch = ctx.launch
        mode = launch.mode
        level = launch.intOption("level", 5)
        flipMode = launch.option("flip", "auto")
        view3D = launch.option("view", "3d") == "3d"
        val (base, inc) = parseTime(launch.option("time", "10+0"))
        clockBase = base; clockInc = inc
        sMove = ctx.audio.load("chess-move", Sfx.PIECE_MOVE)
        sCapture = ctx.audio.load("chess-capture", Sfx.PIECE_CAPTURE)
        sCheck = ctx.audio.load("chess-check", Sfx.CHECK)
        sWin = ctx.audio.load("chess-win", Sfx.WIN)
        sLose = ctx.audio.load("chess-lose", Sfx.LOSE)
        sTick = ctx.audio.load("chess-tick", Sfx.CLICK)
        ctx.graphics.environment.copyFrom(env)
        net = ctx.net
        newGame(whiteForLocal(launch.option("color", "white")))
    }

    // ---------------------------------------------------------------- setup

    /** Decides who plays White and sets up controllers. [localWhite] is used for solo play. */
    private fun newGame(localWhite: Boolean, networkWhiteId: Int = -1) {
        aiJob?.cancel(); ai.stop(); aiJob = null
        hintJob?.cancel(); hintMove = 0
        game.restart()
        showResult = false
        drawOffered = false
        selected = -1; targets = IntArray(0); anim = null; promotionFrom = -1
        val myName = ctx.settings.playerName
        when (mode) {
            PlayMode.SOLO -> {
                val human = if (localWhite) Piece.WHITE else Piece.BLACK
                controllers[human] = Controller.LOCAL
                controllers[human xor 1] = Controller.AI
                viewColor = human
                if (human == Piece.WHITE) { game.whiteName = myName; game.blackName = "OEA AI · ${ChessAi.LEVEL_NAMES[level - 1]}" }
                else { game.blackName = myName; game.whiteName = "OEA AI · ${ChessAi.LEVEL_NAMES[level - 1]}" }
            }
            PlayMode.SAME_DEVICE -> {
                controllers[0] = Controller.LOCAL; controllers[1] = Controller.LOCAL
                viewColor = Piece.WHITE
                val players = ctx.launch.localPlayers
                game.whiteName = players.getOrNull(0)?.name ?: "White"
                game.blackName = players.getOrNull(1)?.name ?: "Black"
            }
            else -> {
                val session = net
                val me = session?.localId ?: 0
                val opponent = session?.players?.firstOrNull { it.id != me }
                opponentId = opponent?.id ?: -1
                val whiteId = if (networkWhiteId >= 0) networkWhiteId else networkWhite(session)
                val myColor = if (whiteId == me) Piece.WHITE else Piece.BLACK
                controllers[myColor] = Controller.LOCAL
                controllers[myColor xor 1] = Controller.REMOTE
                viewColor = myColor
                val oppName = opponent?.name ?: "Opponent"
                if (myColor == Piece.WHITE) { game.whiteName = myName; game.blackName = oppName } else { game.blackName = myName; game.whiteName = oppName }
            }
        }
        game.timeControl = if (clockBase > 0) "${clockBase / 1000}+${clockInc / 1000}" else "-"
        camYawTarget = yawFor(viewColor); camYaw = camYawTarget
        remaining[0] = clockBase; remaining[1] = clockBase
        clockRunning = -1
        if (clockBase > 0) startClock(Piece.WHITE)
        updateStatus()
        maybeStartAi()
    }

    private fun whiteForLocal(color: String): Boolean = when (color) {
        "black" -> false
        "random" -> Random(ctx.launch.seed).nextBoolean()
        else -> true
    }

    /** Same answer on both devices: derived from the host's choice and the shared seed. */
    private fun networkWhite(session: NetSession?): Int {
        val players = session?.players.orEmpty()
        val other = players.firstOrNull { it.id != NetSession.HOST_ID }?.id ?: 1
        return when (session?.matchSettings?.get("color") ?: ctx.launch.option("color", "random")) {
            "white" -> NetSession.HOST_ID
            "black" -> other
            else -> if ((session?.seed ?: ctx.launch.seed) and 1L == 0L) NetSession.HOST_ID else other
        }
    }

    private fun parseTime(v: String): Pair<Long, Long> {
        if (v == "none") return 0L to 0L
        val parts = v.split('+')
        val minutes = parts.getOrNull(0)?.toFloatOrNull() ?: 0f
        val inc = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
        return (minutes * 60_000L).toLong() to (inc * 1000L).toLong()
    }

    private fun yawFor(color: Int) = if (color == Piece.WHITE) MathX.PI_F else 0f

    // ---------------------------------------------------------------- clock

    private fun nowMs() = SystemClock.elapsedRealtime()

    private fun startClock(side: Int) { clockRunning = side; clockLast = nowMs() }

    private fun tickClock() {
        if (clockBase <= 0 || clockRunning < 0 || game.isOver) return
        val now = nowMs()
        remaining[clockRunning] -= now - clockLast
        clockLast = now
        if (remaining[clockRunning] <= 0) {
            remaining[clockRunning] = 0
            onFlag(clockRunning)
        } else if (remaining[clockRunning] < 10_000 && controllers[clockRunning] == Controller.LOCAL) {
            val sec = remaining[clockRunning] / 1000
            if (sec != lowTimeBeep) { lowTimeBeep = sec; ctx.audio.play(sTick, 0.5f) }
        }
    }

    private fun onFlag(side: Int) {
        if (game.isOver) return
        val opponentCanMate = game.board.canMate(side xor 1)
        if (opponentCanMate) game.end(if (side == Piece.WHITE) GameResult.BLACK_WINS else GameResult.WHITE_WINS, EndReason.TIMEOUT)
        else game.end(GameResult.DRAW, EndReason.TIMEOUT_DRAW)
        if (controllers[side] == Controller.LOCAL && net != null) net?.broadcast(MSG_FLAG)
        finishGame()
    }

    // ---------------------------------------------------------------- moves

    private fun isLocalTurn() = !game.isOver && controllers[game.board.side] == Controller.LOCAL && anim == null && promotionFrom < 0

    private fun select(sq: Int) {
        selected = sq
        targets = if (sq >= 0) game.board.legalMoves().filter { Move.from(it) == sq }.map { Move.to(it) }.distinct().toIntArray() else IntArray(0)
    }

    /** Attempts a move by the local player; opens the promotion picker when needed. */
    private fun tryMove(from: Int, to: Int): Boolean {
        val candidates = game.board.legalMoves().filter { Move.from(it) == from && Move.to(it) == to }
        if (candidates.isEmpty()) return false
        if (candidates.any { Move.promo(it) != 0 }) {
            promotionFrom = from; promotionTo = to
            return true
        }
        commit(candidates[0], fromNetwork = false)
        return true
    }

    private fun commit(move: Int, fromNetwork: Boolean) {
        val b = game.board
        val from = Move.from(move); val to = Move.to(move)
        val piece = b.squares[from]
        val capSq = if (Move.isEnPassant(move)) (if (b.side == Piece.WHITE) to - 8 else to + 8) else to
        val captured = b.squares[capSq]
        var rf = -1; var rt = -1; var rook = 0
        if (Move.isCastle(move)) {
            when (to) { 6 -> { rf = 7; rt = 5 }; 2 -> { rf = 0; rt = 3 }; 62 -> { rf = 63; rt = 61 }; else -> { rf = 56; rt = 59 } }
            rook = b.squares[rf]
        }
        val mover = b.side
        val san = b.san(move)
        if (!game.play(move)) return
        anim = Anim(piece, from, to, captured, capSq, rf, rt, rook)
        selected = -1; targets = IntArray(0); hintMove = 0
        // Clock: increment for the mover, then the opponent's clock runs.
        if (clockBase > 0) {
            if (clockRunning == mover) { remaining[mover] -= nowMs() - clockLast }
            remaining[mover] += clockInc
            if (!game.isOver) startClock(mover xor 1) else clockRunning = -1
        }
        val check = game.board.inCheck() && !game.isOver
        ctx.audio.play(if (captured != Piece.EMPTY) sCapture else sMove)
        if (check) { ctx.audio.play(sCheck, 0.8f); showFlash("Check!") }
        ctx.haptics.tick()
        if (!fromNetwork && controllers[mover] == Controller.LOCAL) net?.let { s ->
            s.broadcast(MSG_MOVE) { w -> w.u16(game.board.ply).string(Move.uci(move)).i32(remaining[0].toInt()).i32(remaining[1].toInt()).i64(game.board.hash) }
        }
        if (fromNetwork || controllers[mover] == Controller.AI) ctx.island.event("${if (controllers[mover] == Controller.AI) "AI" else nameOf(mover)}: $san", "♞")
        if (mode == PlayMode.SAME_DEVICE && flipMode == "auto" && !game.isOver) {
            viewColor = game.board.side
            camYawTarget = yawFor(viewColor)
        }
        if (game.isOver) finishGame() else maybeStartAi()
        updateStatus()
    }

    private fun maybeStartAi() {
        if (game.isOver || controllers[game.board.side] != Controller.AI || aiJob != null) return
        val snapshot = game.board.copy()
        val lv = level
        val seed = ctx.launch.seed xor game.board.ply.toLong()
        val startedAt = nowMs()
        val expectedHash = game.board.hash
        // A cancelled search may still be unwinding on a worker: never share its tables.
        val engine = if (aiBusy.compareAndSet(false, true)) ai else ChessAi(16)
        aiJob = ctx.jobs.run({ try { engine.choose(snapshot, lv, Random(seed)) } finally { if (engine === ai) aiBusy.set(false) } }) { result ->
            aiJob = null
            if (result == null || game.isOver || game.board.hash != expectedHash) return@run
            // Don't move instantly: a short pause reads better.
            val wait = 350L - (nowMs() - startedAt)
            if (wait > 0) pendingAiMove = result.move to nowMs() + wait else playAiMove(result.move)
        }
        updateStatus()
    }

    private val aiBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Plays the AI's move; if it is somehow not legal any more, any legal move keeps the game going. */
    private fun playAiMove(move: Int) {
        if (game.isOver || controllers[game.board.side] != Controller.AI) return
        val legal = game.board.legalMoves()
        if (legal.isEmpty()) return
        commit(if (move in legal) move else legal[Random(ctx.launch.seed).nextInt(legal.size)], fromNetwork = false)
    }

    private var pendingAiMove: Pair<Int, Long>? = null

    private fun requestHint() {
        if (!isLocalTurn() || hintJob != null) return
        val snapshot = game.board.copy()
        showFlash("Thinking about a good move…")
        hintJob = ctx.jobs.run({ ChessAi(16).search(snapshot, 12, 900) }) { r ->
            hintJob = null
            if (r != null && snapshot.hash == game.board.hash) {
                hintMove = r.move
                hintUntil = nowMs() + 4000
                showFlash("Hint: ${game.board.san(r.move)}")
            }
        }
    }

    private fun undo() {
        when (mode) {
            PlayMode.SOLO -> {
                aiJob?.cancel(); ai.stop(); aiJob = null; pendingAiMove = null
                // Undo back to the human's turn.
                if (game.undo() && controllers[game.board.side] == Controller.AI) game.undo()
                afterUndo()
            }
            PlayMode.SAME_DEVICE -> { if (game.undo()) afterUndo() }
            else -> net?.let { s ->
                if (game.board.ply == 0 || game.isOver) return
                val target = if (controllers[game.board.side] == Controller.LOCAL) game.board.ply - 2 else game.board.ply - 1
                s.broadcast(MSG_TAKEBACK_REQ) { it.u16(max(0, target)) }
                showFlash("Takeback requested…")
            }
        }
    }

    private fun afterUndo() {
        anim = null; selected = -1; targets = IntArray(0); showResult = false; hintMove = 0
        if (clockBase > 0) startClock(game.board.side)
        if (mode == PlayMode.SAME_DEVICE && flipMode == "auto") { viewColor = game.board.side; camYawTarget = yawFor(viewColor) }
        updateStatus()
        maybeStartAi()
    }

    private fun resign() {
        if (game.isOver) return
        val me = localColor()
        game.end(if (me == Piece.WHITE) GameResult.BLACK_WINS else GameResult.WHITE_WINS, EndReason.RESIGNATION)
        net?.broadcast(MSG_RESIGN)
        finishGame()
    }

    private fun localColor(): Int = when {
        controllers[0] == Controller.LOCAL && controllers[1] != Controller.LOCAL -> Piece.WHITE
        controllers[1] == Controller.LOCAL && controllers[0] != Controller.LOCAL -> Piece.BLACK
        else -> game.board.side
    }

    private fun finishGame() {
        aiJob?.cancel(); aiJob = null
        clockRunning = -1
        showResult = true
        resultShownAt = nowMs()
        val r = game.result
        val me = localColor()
        val won = (r == GameResult.WHITE_WINS && me == Piece.WHITE) || (r == GameResult.BLACK_WINS && me == Piece.BLACK)
        val draw = r == GameResult.DRAW
        if (mode == PlayMode.SAME_DEVICE) ctx.audio.play(sWin) else ctx.audio.play(if (won || draw) sWin else sLose)
        if (mode == PlayMode.SOLO) {
            ctx.storage.putInt("played", ctx.storage.getInt("played") + 1)
            if (won) ctx.storage.putInt("wins_level_$level", ctx.storage.getInt("wins_level_$level") + 1)
        }
        ctx.island.event(resultTitle() + " · " + game.reason.label, "♚", 4000, important = true)
        updateStatus()
    }

    private fun resultTitle(): String = when (game.result) {
        GameResult.WHITE_WINS -> if (mode == PlayMode.SAME_DEVICE || mode == PlayMode.SOLO && controllers[0] != Controller.LOCAL) "${game.whiteName} wins" else if (localColor() == Piece.WHITE) "You win!" else "${game.whiteName} wins"
        GameResult.BLACK_WINS -> if (mode == PlayMode.SAME_DEVICE || mode == PlayMode.SOLO && controllers[1] != Controller.LOCAL) "${game.blackName} wins" else if (localColor() == Piece.BLACK) "You win!" else "${game.blackName} wins"
        GameResult.DRAW -> "Draw"
        GameResult.ONGOING -> ""
    }

    private fun nameOf(color: Int) = if (color == Piece.WHITE) game.whiteName else game.blackName

    private fun updateStatus() {
        val side = game.board.side
        status = when {
            game.isOver -> "${resultTitle()} · ${game.reason.label}"
            opponentAway -> "Opponent disconnected — waiting for them to come back…"
            controllers[side] == Controller.AI -> "OEA AI is thinking…"
            controllers[side] == Controller.REMOTE -> "Waiting for ${nameOf(side)}…"
            mode == PlayMode.SAME_DEVICE -> "${nameOf(side)} to move" + if (game.board.inCheck()) " · Check!" else ""
            game.board.inCheck() -> "Your move · Check!"
            else -> "Your move"
        }
    }

    private fun showFlash(text: String, ms: Long = 1800) { flash = text; flashUntil = nowMs() + ms }

    // ---------------------------------------------------------------- network

    private fun pollNetwork() {
        val s = net ?: return
        s.poll { msg ->
            val r = msg.reader()
            when (msg.type) {
                MSG_MOVE -> {
                    val ply = r.u16(); val uci = r.string(8); val wMs = r.i32().toLong(); val bMs = r.i32().toLong(); val hash = r.i64()
                    val move = game.board.parseMove(uci)
                    if (move == null || controllers[game.board.side] != Controller.REMOTE) { s.send(msg.from, MSG_SYNC_REQ); return@poll }
                    commit(move, fromNetwork = true)
                    if (game.board.ply != ply || game.board.hash != hash) { s.send(msg.from, MSG_SYNC_REQ); return@poll }
                    if (clockBase > 0) { remaining[0] = wMs; remaining[1] = bMs; if (!game.isOver) startClock(game.board.side) }
                }
                MSG_RESIGN -> if (!game.isOver) {
                    val opp = localColor() xor 1
                    game.end(if (opp == Piece.WHITE) GameResult.BLACK_WINS else GameResult.WHITE_WINS, EndReason.RESIGNATION)
                    finishGame()
                }
                MSG_FLAG -> if (!game.isOver) onFlag(localColor() xor 1)
                MSG_DRAW_OFFER -> if (!game.isOver) ctx.ui.confirm("Draw offered", "${nameOf(localColor() xor 1)} offers a draw.", "Accept", "Decline") { ok ->
                    s.broadcast(MSG_DRAW_REPLY) { it.bool(ok) }
                    if (ok && !game.isOver) { game.end(GameResult.DRAW, EndReason.AGREEMENT); finishGame() }
                }
                MSG_DRAW_REPLY -> {
                    drawOffered = false
                    if (r.bool()) { if (!game.isOver) { game.end(GameResult.DRAW, EndReason.AGREEMENT); finishGame() } } else showFlash("Draw declined")
                }
                MSG_TAKEBACK_REQ -> {
                    val target = r.u16()
                    ctx.ui.confirm("Takeback", "${nameOf(localColor() xor 1)} asks to take back a move.", "Allow", "Decline") { ok ->
                        s.broadcast(MSG_TAKEBACK_REPLY) { it.bool(ok).u16(target) }
                        if (ok) applyTakeback(target)
                    }
                }
                MSG_TAKEBACK_REPLY -> { val ok = r.bool(); val target = r.u16(); if (ok) applyTakeback(target) else showFlash("Takeback declined") }
                MSG_SYNC_REQ -> sendSync(msg.from)
                MSG_SYNC -> applySync(r)
                MSG_REMATCH_REQ -> ctx.ui.confirm("Rematch?", "${nameOf(localColor() xor 1)} wants a rematch (colours swap).", "Play again", "No thanks") { ok ->
                    if (!ok) { s.broadcast(MSG_REMATCH_DECLINE); return@confirm }
                    if (s.isHost) startRematch(s) else s.broadcast(MSG_REMATCH_ACCEPT)
                }
                MSG_REMATCH_ACCEPT -> if (s.isHost) startRematch(s)
                MSG_REMATCH_DECLINE -> showFlash("Rematch declined")
                MSG_REMATCH -> { val whiteId = r.u8(); newGame(false, whiteId) }
                NetSession.MSG_PLAYER_LEFT -> if (msg.from == opponentId) { opponentAway = true; awaySince = nowMs(); updateStatus() }
                NetSession.MSG_PLAYER_JOINED -> if (msg.from == opponentId) {
                    opponentAway = false
                    showFlash("Opponent reconnected")
                    if (s.isHost) sendSync(msg.from)
                    updateStatus()
                }
                NetSession.MSG_MATCH_ENDED -> ctx.ui.exit(HostResult(resultTitle().ifEmpty { "Match ended" }))
            }
        }
        if (opponentAway && !game.isOver && nowMs() - awaySince > 45_000) {
            game.end(if (localColor() == Piece.WHITE) GameResult.WHITE_WINS else GameResult.BLACK_WINS, EndReason.ABANDONED)
            finishGame()
        }
    }

    private fun startRematch(s: NetSession) {
        // Colours swap: the current Black plays White.
        val currentWhiteIsMe = controllers[Piece.WHITE] == Controller.LOCAL
        val newWhite = if (currentWhiteIsMe) opponentId else s.localId
        s.broadcast(MSG_REMATCH) { it.u8(newWhite) }
        newGame(false, newWhite)
    }

    private fun applyTakeback(target: Int) {
        while (game.board.ply > target && game.undo()) Unit
        afterUndo()
    }

    private fun sendSync(to: Int) {
        val s = net ?: return
        val moves = game.uciMoves()
        val whiteId = if (controllers[Piece.WHITE] == Controller.LOCAL) s.localId else opponentId
        s.send(to, MSG_SYNC) { w ->
            w.u8(whiteId).varint(moves.size)
            moves.forEach { w.string(it) }
            w.i32(remaining[0].toInt()).i32(remaining[1].toInt()).u8(game.result.ordinal).u8(game.reason.ordinal)
        }
    }

    private fun applySync(r: com.oea.engine.net.ByteReader) {
        val whiteId = r.u8()
        val n = r.varint()
        val moves = (0 until n).map { r.string(8) }
        val wMs = r.i32().toLong(); val bMs = r.i32().toLong()
        val result = GameResult.entries.getOrElse(r.u8()) { GameResult.ONGOING }
        val reason = EndReason.entries.getOrElse(r.u8()) { EndReason.NONE }
        newGame(false, whiteId)
        for (u in moves) { val mv = game.board.parseMove(u) ?: break; game.play(mv) }
        remaining[0] = wMs; remaining[1] = bMs
        if (result != GameResult.ONGOING) { game.end(result, reason); finishGame() } else if (clockBase > 0) startClock(game.board.side)
        anim = null
        updateStatus()
        showFlash("Game synchronised")
    }

    // ---------------------------------------------------------------- frame

    override fun onFixedUpdate(dt: Float) {
        pollNetwork()
    }

    override fun onUpdate(dt: Float) {
        tickClock()
        pendingAiMove?.let { (mv, at) -> if (nowMs() >= at) { pendingAiMove = null; playAiMove(mv) } }
        anim?.let { a ->
            a.t += dt / 0.3f
            if (a.t >= 1f) anim = null
        }
        camYaw = MathX.dampAngle(camYaw, camYawTarget, 6f, dt)
        handleInput()
        publishIsland()
    }

    private fun handleInput() {
        val input = ctx.input
        val g = ctx.graphics
        val free = input.pointers.filter { it.isDown && it.isFree }
        // Two fingers: pinch to zoom, twist/drag to orbit (3D only).
        if (view3D && free.size >= 2) {
            val a = free[0]; val b = free[1]
            val d1 = hypot(a.x - b.x, a.y - b.y)
            val d0 = hypot((a.x - a.dx) - (b.x - b.dx), (a.y - a.dy) - (b.y - b.dy))
            if (d0 > 1f) camDist = MathX.clamp(camDist * d0 / d1, 9f, 22f)
            camPitch = MathX.clamp(camPitch + (a.dy + b.dy) * 0.5f / g.height * 2.2f, 0.45f, 1.45f)
            pressPointer = -1; dragging = false; orbiting = true
            return
        }
        for (p in input.pointers) {
            if (!p.isFree && p.id != pressPointer) continue
            if (p.justPressed && pressPointer < 0) {
                pressPointer = p.id; pressX = p.x; pressY = p.y
                pressSq = squareAt(p.x, p.y)
                dragging = false; orbiting = false
            }
            if (p.id != pressPointer) continue
            if (p.isDown) {
                val moved = hypot(p.x - pressX, p.y - pressY) > 12f * g.density
                val ownPiece = pressSq >= 0 && isLocalTurn() && game.board.squares[pressSq] != Piece.EMPTY && Piece.color(game.board.squares[pressSq]) == game.board.side
                if (moved && !dragging && !orbiting) {
                    if (ownPiece) { dragging = true; select(pressSq) } else orbiting = view3D
                }
                if (dragging) { dragX = p.x; dragY = p.y }
                if (orbiting && view3D) {
                    camYawTarget -= p.dx / g.width * 3.2f
                    camYaw = camYawTarget
                    camPitch = MathX.clamp(camPitch + p.dy / g.height * 2.2f, 0.45f, 1.45f)
                }
            }
            if (p.justReleased) {
                val sq = squareAt(p.x, p.y)
                if (dragging) {
                    if (sq < 0 || !tryMove(selected, sq)) Unit
                } else if (!orbiting) {
                    onTapSquare(sq)
                }
                pressPointer = -1; dragging = false; orbiting = false
            }
        }
    }

    private fun onTapSquare(sq: Int) {
        if (sq < 0) { select(-1); return }
        if (!isLocalTurn()) return
        val p = game.board.squares[sq]
        if (selected >= 0 && sq in targets) { tryMove(selected, sq); return }
        if (p != Piece.EMPTY && Piece.color(p) == game.board.side) { if (selected == sq) select(-1) else { select(sq); ctx.haptics.tick() } }
        else select(-1)
    }

    /** Screen point → board square, or -1. */
    private fun squareAt(x: Float, y: Float): Int {
        if (!view3D) {
            if (boardSize <= 0f) return -1
            val fx = floor((x - boardX) / (boardSize / 8f)).toInt()
            val fy = floor((y - boardY) / (boardSize / 8f)).toInt()
            if (fx !in 0..7 || fy !in 0..7) return -1
            val file = if (viewColor == Piece.WHITE) fx else 7 - fx
            val rank = if (viewColor == Piece.WHITE) 7 - fy else fy
            return rank * 8 + file
        }
        val g = ctx.graphics
        camera.screenRay(x, y, g.width.toFloat(), g.height.toFloat(), ray)
        // Pieces first (tall objects), then the board plane.
        var best = -1; var bestT = Float.MAX_VALUE
        for (s in 0 until 64) {
            val pc = game.board.squares[s]
            if (pc == Piece.EMPTY) continue
            val h = PIECE_HEIGHT[Piece.type(pc)]
            val t = Intersect.rayCylinderY(ray.origin.x, ray.origin.y, ray.origin.z, ray.dir.x, ray.dir.y, ray.dir.z, sqX(s), sqZ(s), 0.3f, 0f, h)
            if (t >= 0f && t < bestT) { bestT = t; best = s }
        }
        if (best >= 0) return best
        val t = Intersect.rayPlaneY(ray.origin.y, ray.dir.y, 0f)
        if (t < 0f) return -1
        val px = ray.origin.x + ray.dir.x * t; val pz = ray.origin.z + ray.dir.z * t
        val file = floor(4f - px).toInt(); val rank = floor(pz + 4f).toInt()
        return if (file in 0..7 && rank in 0..7) rank * 8 + file else -1
    }

    private fun sqX(s: Int) = 3.5f - (s and 7)
    private fun sqZ(s: Int) = (s shr 3) - 3.5f

    private fun publishIsland() {
        if (game.isOver) return
        val side = game.board.side
        val mine = controllers[side] == Controller.LOCAL
        val opponent = when (mode) {
            PlayMode.SOLO -> "OEA AI"
            PlayMode.SAME_DEVICE -> "pass & play"
            else -> nameOf(localColor() xor 1)
        }
        val clock = if (clockBase > 0) formatClock(remaining[if (mode == PlayMode.SAME_DEVICE) side else localColor()]) else "Move ${game.board.fullmove}"
        ctx.island.update(LiveActivity(
            title = "Chess vs $opponent",
            subtitle = status,
            compactText = if (mine) "Your move" else "Their move",
            trailing = clock,
            urgent = mine && clockBase > 0 && remaining[localColor()] < 30_000,
            preferred = IslandStyle.CAMERA,
        ))
    }

    private fun formatClock(ms: Long): String {
        val total = max(0L, ms)
        val s = total / 1000
        return if (total < 10_000) String.format(Locale.US, "%d.%d", s, (total % 1000) / 100) else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    override fun onPause() {
        // Solo games pause the clock; networked clocks keep running (the opponent is waiting).
        if (mode == PlayMode.SOLO || mode == PlayMode.SAME_DEVICE) { tickClock(); clockRunning = -1 }
    }

    override fun onResume() {
        if ((mode == PlayMode.SOLO || mode == PlayMode.SAME_DEVICE) && clockBase > 0 && !game.isOver) startClock(game.board.side)
    }

    override fun keepsRunningInBackground(): Boolean = net != null

    override fun onBack(): Boolean {
        if (promotionFrom >= 0) { promotionFrom = -1; return true }
        if (selected >= 0) { select(-1); return true }
        openMenu()
        return true
    }


    /**
     * A deliberate flip from the players: if the board was set to turn to the
     * player to move, per-move turning pauses (otherwise the next move would
     * immediately undo the flip, which feels broken). Re-enable it from the
     * menu, or at launch via the Board option.
     */
    private fun flipManually() {
        viewColor = viewColor xor 1
        camYawTarget = yawFor(viewColor)
        if (mode == PlayMode.SAME_DEVICE && flipMode == "auto") {
            flipMode = "off"
            showFlash("Board turning paused — re-enable in the menu")
        }
    }

    private fun openMenu() {
        val items = ArrayList<MenuItem>()
        items += MenuItem("resume", "Resume")
        if (mode == PlayMode.SOLO || mode == PlayMode.SAME_DEVICE) items += MenuItem("new", "New game", "Start over with the same settings")
        if (net != null && !game.isOver) items += MenuItem("draw", "Offer a draw")
        if (!game.isOver && mode != PlayMode.SAME_DEVICE) items += MenuItem("resign", "Resign", destructive = true)
        items += MenuItem("view", if (view3D) "Switch to 2D board" else "Switch to 3D board")
        items += MenuItem("flip", "Flip board")
        if (mode == PlayMode.SAME_DEVICE) items += MenuItem("autoflip", if (flipMode == "auto") "Board turning: on" else "Board turning: off", "Turn the board to the player to move after every move")
        items += MenuItem("pgn", "Share game (PGN)", "Moves in standard notation, for any chess app")
        items += MenuItem("exit", "Leave game", destructive = true)
        ctx.ui.menu("Chess", status, items) { id ->
            when (id) {
                "new" -> newGame(controllers[Piece.WHITE] == Controller.LOCAL)
                "draw" -> if (!drawOffered) { drawOffered = true; net?.broadcast(MSG_DRAW_OFFER); showFlash("Draw offered") }
                "resign" -> ctx.ui.confirm("Resign?", "The game ends and your opponent wins.", "Resign") { ok -> if (ok) resign() }
                "view" -> view3D = !view3D
                "flip" -> flipManually()
                "autoflip" -> {
                    flipMode = if (flipMode == "auto") "off" else "auto"
                    if (flipMode == "auto" && !game.isOver) { viewColor = game.board.side; camYawTarget = yawFor(viewColor) }
                    showFlash(if (flipMode == "auto") "Board turns to the player to move" else "Board stays put")
                }
                "pgn" -> sharePgn()
                "exit" -> leave()
            }
        }
    }

    private fun sharePgn() {
        val date = SimpleDateFormat("yyyy.MM.dd", Locale.US).format(Date())
        ctx.ui.share(game.pgn(date), "Share chess game")
    }

    private fun leave() {
        if (net != null && !game.isOver) {
            ctx.ui.confirm("Leave the match?", "Leaving counts as resigning.", "Leave") { ok -> if (ok) { resign(); ctx.ui.exit(HostResult("Resigned")) } }
        } else ctx.ui.exit(HostResult(resultTitle().ifEmpty { "Left" }))
    }

    override fun onIslandAction(actionId: String) = Unit

    override fun onDestroy() {
        aiJob?.cancel(); ai.stop()
        hintJob?.cancel()
    }

    // ---------------------------------------------------------------- rendering

    override fun onRender(frame: Frame) {
        frame.clear(0xFF161A20.toInt())
        if (view3D) render3D(frame)
        frame.ui { hud -> renderHud(hud) }
    }

    private fun render3D(frame: Frame) {
        val aspect = frame.width.toFloat() / frame.height
        // Fit the whole board horizontally in portrait and vertically in landscape.
        val halfH = atan(4.95f / camDist)
        camera.fovY = MathX.clamp(max(2f * atan(tan(halfH) / aspect), 2f * atan(4.2f / camDist)), 0.5f, 1.45f)
        camera.target.set(0f, 0f, 0f)
        camera.orbit(camYaw, camPitch, camDist)
        camera.near = 0.5f; camera.far = 80f
        frame.world(camera, environment = env) { r ->
            r.draw(tableMesh, ChessArt.table, m.idt())
            r.draw(ChessArt.frameMesh, ChessArt.frame, m.idt())
            r.draw(ChessArt.lightTiles, ChessArt.lightSquare, m.idt())
            r.draw(ChessArt.darkTiles, ChessArt.darkSquare, m.idt())
            val last = game.board.lastMove()
            if (last != Move.NONE) { highlight(r, Move.from(last), lastMoveMat); highlight(r, Move.to(last), lastMoveMat) }
            if (selected >= 0) highlight(r, selected, selectMat)
            if (hintMove != 0 && nowMs() < hintUntil) { highlight(r, Move.from(hintMove), hintMat); highlight(r, Move.to(hintMove), hintMat) }
            if (game.board.inCheck() && !game.isOver || game.reason == EndReason.CHECKMATE) {
                val k = game.board.kingSquare[game.board.side]
                r.draw(Mesh.unitQuad, checkMat, sqX(k), 0.006f, sqZ(k), 0f, 0f, 0f, 1.25f, 1f, 1.25f)
            }
            for (t in targets) {
                if (game.board.squares[t] != Piece.EMPTY) r.draw(ringMesh, dotMat, sqX(t), 0.004f, sqZ(t))
                else r.draw(dotMesh, dotMat, sqX(t), 0.004f, sqZ(t))
            }
            val a = anim
            val blobs = !r.shadowMapsActive
            for (s in 0 until 64) {
                val pc = game.board.squares[s]
                if (pc == Piece.EMPTY) continue
                if (a != null && (s == a.to || s == a.rookTo)) continue
                if (dragging && s == selected) continue
                drawPiece(r, pc, sqX(s), 0f, sqZ(s), blobs)
            }
            if (a != null) {
                val k = Ease3.outCubic(MathX.clamp01(a.t))
                val hop = if (Piece.type(a.piece) == Piece.KNIGHT) 0.55f else 0.18f
                val x = MathX.lerp(sqX(a.from), sqX(a.to), k); val z = MathX.lerp(sqZ(a.from), sqZ(a.to), k)
                val placed = game.board.squares[a.to].takeIf { it != Piece.EMPTY } ?: a.piece
                drawPiece(r, if (k > 0.5f) placed else a.piece, x, sin(k * MathX.PI_F) * hop, z, blobs)
                if (a.rook != 0) drawPiece(r, a.rook, MathX.lerp(sqX(a.rookFrom), sqX(a.rookTo), k), 0f, MathX.lerp(sqZ(a.rookFrom), sqZ(a.rookTo), k), blobs)
                if (a.captured != Piece.EMPTY && k < 1f) {
                    val sc = 1f - k
                    val mat = if (Piece.color(a.captured) == Piece.WHITE) ChessArt.ivory else ChessArt.ebony
                    r.draw(ChessArt.mesh(Piece.type(a.captured)), mat, sqX(a.capturedSq), -0.3f * k, sqZ(a.capturedSq), knightYaw(a.captured), 0f, 0f, sc, sc, sc)
                }
            }
            if (dragging && selected >= 0) {
                camera.screenRay(dragX, dragY, frame.width.toFloat(), frame.height.toFloat(), ray)
                val t = Intersect.rayPlaneY(ray.origin.y, ray.dir.y, 0.35f)
                if (t > 0f) drawPiece(r, game.board.squares[selected], ray.origin.x + ray.dir.x * t, 0.35f, ray.origin.z + ray.dir.z * t, blobs)
            }
        }
    }

    private fun highlight(r: com.oea.engine.graphics.Renderer3D, sq: Int, mat: Material) =
        r.draw(Mesh.unitQuad, mat, sqX(sq), 0.003f, sqZ(sq), 0f, 0f, 0f, 0.98f, 1f, 0.98f)

    private fun knightYaw(pc: Int) = if (Piece.color(pc) == Piece.WHITE) 0f else MathX.PI_F

    private fun drawPiece(r: com.oea.engine.graphics.Renderer3D, pc: Int, x: Float, y: Float, z: Float, blob: Boolean) {
        val mat = if (Piece.color(pc) == Piece.WHITE) ChessArt.ivory else ChessArt.ebony
        r.draw(ChessArt.mesh(Piece.type(pc)), mat, x, y, z, knightYaw(pc), 1f)
        if (blob && y < 0.05f) r.blobShadow(x, 0f, z, 0.36f, 0.5f)
    }

    private fun renderHud(hud: Hud) {
        val w = hud.width; val h = hud.height
        val d = hud.density
        hud.accent = ACCENT
        val panelH = 58f * d
        val top = hud.safeTop + 10f * d
        val buttonsH = 64f * d
        val bottomPanelY = h - hud.safeBottom - buttonsH - panelH - 14f * d
        val faceToFace = mode == PlayMode.SAME_DEVICE && flipMode == "face"
        // Board area for 2D.
        if (!view3D) {
            val avail = bottomPanelY - (top + panelH) - 24f * d
            boardSize = min(w - 20f * d, avail)
            boardX = (w - boardSize) / 2f
            boardY = top + panelH + 12f * d + (avail - boardSize) / 2f
            draw2DBoard(hud)
        }
        val topColor = viewColor xor 1
        if (faceToFace) {
            hud.batch.flush()
            playerPanel(hud, topColor, 12f * d, top, w - 24f * d, panelH, rotated = true)
        } else playerPanel(hud, topColor, 12f * d, top, w - 24f * d, panelH, rotated = false)
        playerPanel(hud, viewColor, 12f * d, bottomPanelY, w - 24f * d, panelH, rotated = false)
        // Status and recent moves.
        val statusY = top + panelH + 8f * d
        if (view3D) hud.text(status, w / 2f, statusY, 15f, Colors.WHITE, Align.CENTER, bold = true)
        val moves = recentMoves()
        if (moves.isNotEmpty()) hud.text(moves, w / 2f, bottomPanelY - 24f * d, 13f, 0xCCFFFFFF.toInt(), Align.CENTER)
        if (nowMs() < flashUntil) {
            val fw = hud.measure(flash, 16f, true) + 32f * d
            hud.panel(w / 2f - fw / 2f, h * 0.42f, fw, 40f * d, 0xE0101418.toInt(), 20f)
            hud.text(flash, w / 2f, h * 0.42f + 10f * d, 16f, Colors.WHITE, Align.CENTER, bold = true, shadow = false)
        }
        // Bottom buttons.
        val by = h - hud.safeBottom - buttonsH + 6f * d
        val labels = buildList {
            add("menu" to "☰")
            if (mode != PlayMode.SAME_DEVICE || game.board.ply > 0) add("undo" to "↶")
            if (mode == PlayMode.SOLO || mode == PlayMode.SAME_DEVICE) add("hint" to "★")
            add("flip" to "⇅")
            add("view" to if (view3D) "2D" else "3D")
        }
        val slot = (w - 24f * d) / labels.size
        labels.forEachIndexed { i, (id, glyph) ->
            val cx = 12f * d + slot * (i + 0.5f)
            if (hud.iconButton("btn_$id", glyph, cx, by + 24f * d, 24f)) onButton(id)
        }
        if (promotionFrom >= 0) renderPromotion(hud)
        if (showResult && game.isOver) renderResult(hud)
    }

    private fun onButton(id: String) {
        when (id) {
            "menu" -> openMenu()
            "undo" -> undo()
            "hint" -> requestHint()
            "flip" -> flipManually()
            "view" -> view3D = !view3D
        }
    }

    private fun recentMoves(): String {
        val sans = game.sanMoves
        if (sans.isEmpty()) return ""
        val start = max(0, sans.size - 6)
        val sb = StringBuilder()
        val startBoard = Board().also { it.setFen(game.startFen) }
        val blackFirst = startBoard.side == Piece.BLACK
        for (i in start until sans.size) {
            val plyIndex = i + if (blackFirst) 1 else 0
            if (plyIndex % 2 == 0) sb.append("${startBoard.fullmove + plyIndex / 2}. ")
            else if (i == start) sb.append("${startBoard.fullmove + plyIndex / 2}… ")
            sb.append(sans[i]).append("  ")
        }
        return sb.toString().trim()
    }

    private fun playerPanel(hud: Hud, color: Int, x: Float, y: Float, w: Float, h: Float, rotated: Boolean) {
        val d = hud.density
        val active = !game.isOver && game.board.side == color
        val bg = if (active) 0xE6243042.toInt() else 0xB3141820.toInt()
        val drawAt = { px: Float, py: Float -> if (rotated) (hud.width - px) to (hud.height - py) else px to py }
        if (rotated) {
            // Face-to-face: draw this panel upside down for the player across the table.
            hud.batch.end()
            val rot = Mat4().ortho(0f, hud.width, hud.height, 0f, -1f, 1f).mul(Mat4().setTRS(hud.width / 2f, hud.height / 2f, 0f, roll = MathX.PI_F)).mul(Mat4().setTranslation(-hud.width / 2f, -hud.height / 2f, 0f))
            hud.batch.begin(rot)
            val ry = hud.height - y - h
            panelContent(hud, color, x, ry, w, h, active, bg)
            hud.batch.end()
            hud.batch.beginPixels(hud.width, hud.height)
        } else panelContent(hud, color, x, y, w, h, active, bg)
        @Suppress("UNUSED_VARIABLE") val unused = drawAt
        @Suppress("UNUSED_VARIABLE") val unusedD = d
    }

    private fun panelContent(hud: Hud, color: Int, x: Float, y: Float, w: Float, h: Float, active: Boolean, bg: Int) {
        val d = hud.density
        hud.panel(x, y, w, h, bg, 18f)
        if (active) hud.batch.roundRect(x, y + h - 3f * d, w, 3f * d, 1.5f * d, ACCENT)
        val dot = if (color == Piece.WHITE) 0xFFF1E6D2.toInt() else 0xFF2B2420.toInt()
        hud.batch.circle(x + 26f * d, y + h / 2f, 13f * d, dot)
        hud.batch.ring(x + 26f * d, y + h / 2f, 13f * d, 1.5f * d, 0x88FFFFFF.toInt())
        hud.text(nameOf(color), x + 48f * d, y + 9f * d, 15f, Colors.WHITE, bold = true, shadow = false)
        // Captured material and advantage.
        val (icons, adv) = capturedBy(color)
        var cx = x + 48f * d
        val iy = y + 32f * d
        val size = 16f * d
        for (pc in icons.take(15)) {
            ChessArt.iconUv(pc, uv)
            hud.batch.draw(ChessArt.icons, cx, iy, size, size, Colors.WHITE, uv[0], uv[1], uv[2], uv[3])
            cx += size * 0.62f
        }
        if (adv > 0) hud.text("+${adv / 100}", cx + 6f * d, iy, 12f, 0xCCFFFFFF.toInt(), shadow = false)
        if (clockBase > 0) {
            val t = remaining[color]
            val cw = 92f * d
            val low = t < 30_000
            hud.batch.roundRect(x + w - cw - 8f * d, y + 9f * d, cw, h - 18f * d, 12f * d, if (active) (if (low) 0xFFE5484D.toInt() else 0xFFF1F3F5.toInt()) else 0x33FFFFFF)
            hud.text(formatClock(t), x + w - 8f * d - cw / 2f, y + h / 2f - 11f * d, 20f, if (active) (if (low) Colors.WHITE else 0xFF101418.toInt()) else Colors.WHITE, Align.CENTER, bold = true, shadow = false)
        }
    }

    /** Pieces [color] has captured, plus the material balance in their favour. */
    private fun capturedBy(color: Int): Pair<List<Int>, Int> {
        val counts = IntArray(16)
        for (p in game.board.squares) if (p != Piece.EMPTY) counts[p]++
        val start = intArrayOf(0, 8, 2, 2, 2, 1, 1)
        val list = ArrayList<Int>()
        var mine = 0; var theirs = 0
        for (t in 1..5) {
            val enemy = Piece.make(color xor 1, t)
            val missing = max(0, start[t] - counts[enemy])
            repeat(missing) { list += enemy }
            mine += counts[Piece.make(color, t)] * Piece.VALUES[t]
            theirs += counts[enemy] * Piece.VALUES[t]
        }
        return list to (mine - theirs)
    }

    private fun draw2DBoard(hud: Hud) {
        val b = hud.batch
        val s = boardSize / 8f
        val d = hud.density
        b.roundRect(boardX - 8f * d, boardY - 8f * d, boardSize + 16f * d, boardSize + 16f * d, 10f * d, 0xFF3E2716.toInt())
        val last = game.board.lastMove()
        for (row in 0 until 8) for (col in 0 until 8) {
            val file = if (viewColor == Piece.WHITE) col else 7 - col
            val rank = if (viewColor == Piece.WHITE) 7 - row else row
            val sq = rank * 8 + file
            val light = (rank + file) % 2 == 1
            val x = boardX + col * s; val y = boardY + row * s
            b.rect(x, y, s, s, if (light) 0xFFF0D9B5.toInt() else 0xFFB58863.toInt())
            if (last != Move.NONE && (sq == Move.from(last) || sq == Move.to(last))) b.rect(x, y, s, s, 0x66F6E05A)
            if (sq == selected) b.rect(x, y, s, s, 0x7746B4FF)
            if (hintMove != 0 && nowMs() < hintUntil && (sq == Move.from(hintMove) || sq == Move.to(hintMove))) b.rect(x, y, s, s, 0x7766E08A)
            if (game.board.inCheck() && sq == game.board.kingSquare[game.board.side]) b.glow(x + s / 2f, y + s / 2f, s * 0.6f, 0xDDFF3B30.toInt())
            if (col == 0) hud.font.draw(b, "${rank + 1}", x + 3f * d, y + 2f * d, 10f * d, if (light) 0xFFB58863.toInt() else 0xFFF0D9B5.toInt())
            if (row == 7) hud.font.draw(b, "${'a' + file}", x + s - 9f * d, y + s - 14f * d, 10f * d, if (light) 0xFFB58863.toInt() else 0xFFF0D9B5.toInt())
        }
        val a = anim
        for (sq in 0 until 64) {
            val pc = game.board.squares[sq]
            if (pc == Piece.EMPTY || (dragging && sq == selected) || (a != null && (sq == a.to || sq == a.rookTo))) continue
            val (x, y) = sqScreen(sq)
            drawIcon(hud, pc, x, y, s)
        }
        if (a != null) {
            val k = Ease3.outCubic(MathX.clamp01(a.t))
            val (fx, fy) = sqScreen(a.from); val (tx, ty) = sqScreen(a.to)
            drawIcon(hud, game.board.squares[a.to].takeIf { it != Piece.EMPTY } ?: a.piece, MathX.lerp(fx, tx, k), MathX.lerp(fy, ty, k), s)
            if (a.rook != 0) {
                val (rfx, rfy) = sqScreen(a.rookFrom); val (rtx, rty) = sqScreen(a.rookTo)
                drawIcon(hud, a.rook, MathX.lerp(rfx, rtx, k), MathX.lerp(rfy, rty, k), s)
            }
        }
        for (t in targets) {
            val (x, y) = sqScreen(t)
            if (game.board.squares[t] != Piece.EMPTY) b.ring(x + s / 2f, y + s / 2f, s * 0.44f, s * 0.07f, 0x66101418)
            else b.circle(x + s / 2f, y + s / 2f, s * 0.15f, 0x55101418)
        }
        if (dragging && selected >= 0) drawIcon(hud, game.board.squares[selected], dragX - s * 0.6f, dragY - s * 0.9f, s * 1.2f)
    }

    private fun sqScreen(sq: Int): Pair<Float, Float> {
        val s = boardSize / 8f
        val file = sq and 7; val rank = sq shr 3
        val col = if (viewColor == Piece.WHITE) file else 7 - file
        val row = if (viewColor == Piece.WHITE) 7 - rank else rank
        return (boardX + col * s) to (boardY + row * s)
    }

    private fun drawIcon(hud: Hud, pc: Int, x: Float, y: Float, s: Float) {
        ChessArt.iconUv(pc, uv)
        hud.batch.draw(ChessArt.icons, x, y, s, s, Colors.WHITE, uv[0], uv[1], uv[2], uv[3])
    }

    private fun renderPromotion(hud: Hud) {
        val d = hud.density
        hud.dim(0.5f)
        val w = min(hud.width - 40f * d, 340f * d)
        val h = 150f * d
        val x = (hud.width - w) / 2f; val y = (hud.height - h) / 2f
        hud.solidPanel(x, y, w, h)
        hud.text("Promote to", x + w / 2f, y + 14f * d, 17f, Colors.WHITE, Align.CENTER, bold = true, shadow = false)
        val types = intArrayOf(Piece.QUEEN, Piece.ROOK, Piece.BISHOP, Piece.KNIGHT)
        val cell = (w - 40f * d) / 4f
        types.forEachIndexed { i, t ->
            val cx = x + 20f * d + i * cell
            val cy = y + 52f * d
            val pc = Piece.make(game.board.side, t)
            hud.batch.roundRect(cx + 4f * d, cy, cell - 8f * d, cell - 8f * d, 12f * d, 0x33FFFFFF)
            drawIcon(hud, pc, cx + 8f * d, cy + 4f * d, cell - 16f * d)
            if (hud.hotspot("promo_$t", cx, cy, cell, cell)) {
                val mv = game.board.legalMoves().firstOrNull { Move.from(it) == promotionFrom && Move.to(it) == promotionTo && Move.promo(it) == t }
                promotionFrom = -1
                if (mv != null) commit(mv, false)
            }
        }
    }

    private fun renderResult(hud: Hud) {
        val d = hud.density
        val t = MathX.clamp01((nowMs() - resultShownAt) / 350f)
        hud.dim(0.55f * t)
        val w = min(hud.width - 32f * d, 360f * d)
        val h = 250f * d
        val x = (hud.width - w) / 2f
        val y = hud.height / 2f - h / 2f + (1f - Ease3.outCubic(t)) * 60f * d
        hud.solidPanel(x, y, w, h, 0xF2141922.toInt(), 26f)
        hud.text(resultTitle(), x + w / 2f, y + 22f * d, 26f, Colors.WHITE, Align.CENTER, bold = true, shadow = false)
        hud.text(game.reason.label, x + w / 2f, y + 62f * d, 15f, 0xCCFFFFFF.toInt(), Align.CENTER, shadow = false)
        hud.text("${game.sanMoves.size} moves · ${game.result.pgn}", x + w / 2f, y + 86f * d, 13f, 0x99FFFFFF.toInt(), Align.CENTER, shadow = false)
        val bw = (w - 48f * d) / 2f
        val rematchLabel = if (net != null) "Rematch" else "Play again"
        if (hud.button("res_again", rematchLabel, x + 16f * d, y + 120f * d, bw, 46f * d)) {
            if (net != null) { net?.broadcast(MSG_REMATCH_REQ); showFlash("Rematch requested…"); showResult = false }
            else newGame(if (mode == PlayMode.SOLO) controllers[Piece.WHITE] != Controller.LOCAL else true)
        }
        if (hud.button("res_pgn", "Share PGN", x + 32f * d + bw, y + 120f * d, bw, 46f * d, ButtonStyle.SECONDARY)) sharePgn()
        if (hud.button("res_review", "Review board", x + 16f * d, y + 178f * d, bw, 46f * d, ButtonStyle.GHOST)) showResult = false
        if (hud.button("res_exit", "Exit", x + 32f * d + bw, y + 178f * d, bw, 46f * d, ButtonStyle.GHOST)) ctx.ui.exit(HostResult(resultTitle(), won = resultTitle() == "You win!"))
    }

    private object Ease3 { fun outCubic(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t) }

    companion object {
        const val ID = "chess"
        private val ACCENT = 0xFFE8B04A.toInt()
        private val PIECE_HEIGHT = floatArrayOf(0f, 0.6f, 0.8f, 0.9f, 0.75f, 1.0f, 1.1f)

        private const val MSG_MOVE = 32
        private const val MSG_RESIGN = 33
        private const val MSG_DRAW_OFFER = 34
        private const val MSG_DRAW_REPLY = 35
        private const val MSG_TAKEBACK_REQ = 36
        private const val MSG_TAKEBACK_REPLY = 37
        private const val MSG_SYNC_REQ = 38
        private const val MSG_SYNC = 39
        private const val MSG_FLAG = 40
        private const val MSG_REMATCH_REQ = 41
        private const val MSG_REMATCH_ACCEPT = 42
        private const val MSG_REMATCH_DECLINE = 43
        private const val MSG_REMATCH = 44

        val descriptor = GameDescriptor(
            id = ID,
            title = "Chess",
            tagline = "Full rules · 10 AI levels · 3D and 2D",
            description = "Complete chess with castling, en passant, promotion, every draw rule and clocks. " +
                "Play the AI, pass and play on one phone, or challenge friends over Wi-Fi, hotspot or online with a code.",
            accent = ACCENT,
            glyph = "♞",
            orientation = GameOrientation.PORTRAIT,
            dimension = GameDimension.D3,
            minPlayers = 1,
            maxPlayers = 2,
            modes = setOf(PlayMode.SOLO, PlayMode.SAME_DEVICE, PlayMode.LAN, PlayMode.ONLINE),
            options = listOf(
                GameOption("level", "AI strength", ChessAi.LEVEL_NAMES.mapIndexed { i, n -> Choice("${i + 1}", "${i + 1} · $n") }, "5", setOf(PlayMode.SOLO)),
                GameOption("color", "Your colour", listOf(Choice("white", "White"), Choice("black", "Black"), Choice("random", "Random")), "white",
                    setOf(PlayMode.SOLO, PlayMode.LAN, PlayMode.ONLINE)),
                GameOption("time", "Clock", listOf(
                    Choice("none", "No clock"), Choice("1+0", "Bullet 1 min"), Choice("3+2", "Blitz 3+2"), Choice("5+0", "Blitz 5 min"),
                    Choice("10+0", "Rapid 10 min"), Choice("15+10", "Rapid 15+10"), Choice("30+0", "Classical 30 min"),
                ), "10+0"),
                GameOption("flip", "Board", listOf(Choice("auto", "Turn to the player to move"), Choice("face", "Face to face"), Choice("off", "Fixed")), "auto",
                    setOf(PlayMode.SAME_DEVICE)),
                GameOption("view", "View", listOf(Choice("3d", "3D board"), Choice("2d", "2D board")), "3d"),
            ),
            sameDeviceLabel = "Pass & play",
            sameDevicePlayers = 2,
            preferredIsland = IslandStyle.CAMERA,
            version = 1,
            factory = { OeaChess() },
        )
    }

    @Suppress("unused") private fun unusedAbs() = abs(0f)
}
